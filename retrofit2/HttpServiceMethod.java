/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ownbranch.retrofit2;

import static retrofit2.Utils.getRawType;
import static retrofit2.Utils.methodError;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import javax.annotation.Nullable;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.CallAdapter;
import retrofit2.Converter;
import retrofit2.KotlinExtensions;
import retrofit2.OkHttpCall;
import retrofit2.RequestFactory;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.ServiceMethod;
import retrofit2.SkipCallbackExecutorImpl;
import retrofit2.Utils;

/** Adapts an invocation of an interface method into an HTTP call. */
// invoke来实现核心逻辑
abstract class HttpServiceMethod<ResponseT, ReturnT> extends retrofit2.ServiceMethod<ReturnT> {
  /**
   * Inspects the annotations on an interface method to construct a reusable service method that
   * speaks HTTP. This requires potentially-expensive reflection so it is best to build each service
   * method only once and reuse it.
   */
  static <ResponseT, ReturnT> HttpServiceMethod<ResponseT, ReturnT> parseAnnotations(
          retrofit2.Retrofit retrofit, Method method, retrofit2.RequestFactory requestFactory) {
    boolean isKotlinSuspendFunction = requestFactory.isKotlinSuspendFunction;
    boolean continuationWantsResponse = false;
    boolean continuationBodyNullable = false;
    boolean continuationIsUnit = false;

    Annotation[] annotations = method.getAnnotations();
    Type adapterType;
    if (isKotlinSuspendFunction) {
      Type[] parameterTypes = method.getGenericParameterTypes();
      Type responseType =
          retrofit2.Utils.getParameterLowerBound(
              0, (ParameterizedType) parameterTypes[parameterTypes.length - 1]);
      if (getRawType(responseType) == retrofit2.Response.class && responseType instanceof ParameterizedType) {
        // Unwrap the actual body type from Response<T>.
        responseType = retrofit2.Utils.getParameterUpperBound(0, (ParameterizedType) responseType);
        continuationWantsResponse = true;
      } else {
        continuationIsUnit = retrofit2.Utils.isUnit(responseType);
        // TODO figure out if type is nullable or not
        // Metadata metadata = method.getDeclaringClass().getAnnotation(Metadata.class)
        // Find the entry for method
        // Determine if return type is nullable or not
      }

      adapterType = new retrofit2.Utils.ParameterizedTypeImpl(null, retrofit2.Call.class, responseType);
      annotations = SkipCallbackExecutorImpl.ensurePresent(annotations);
    } else {
      adapterType = method.getGenericReturnType();
    }

    retrofit2.CallAdapter<ResponseT, ReturnT> callAdapter =
        createCallAdapter(retrofit, method, adapterType, annotations);
    Type responseType = callAdapter.responseType();
    if (responseType == okhttp3.Response.class) {
      throw methodError(
          method,
          "'"
              + getRawType(responseType).getName()
              + "' is not a valid response body type. Did you mean ResponseBody?");
    }
    if (responseType == retrofit2.Response.class) {
      throw methodError(method, "Response must include generic type (e.g., Response<String>)");
    }
    // TODO support Unit for Kotlin?
    if (requestFactory.httpMethod.equals("HEAD")
        && !Void.class.equals(responseType)
        && !retrofit2.Utils.isUnit(responseType)) {
      throw methodError(method, "HEAD method must use Void or Unit as response type.");
    }

    retrofit2.Converter<ResponseBody, ResponseT> responseConverter =
        createResponseConverter(retrofit, method, responseType);

    okhttp3.Call.Factory callFactory = retrofit.callFactory;
    // 1.5
    // 其实就是这个最中adapted的实现
    if (!isKotlinSuspendFunction) {
      return new CallAdapted<>(requestFactory, callFactory, responseConverter, callAdapter);
    } else if (continuationWantsResponse) {
      //noinspection unchecked Kotlin compiler guarantees ReturnT to be Object.
      return (HttpServiceMethod<ResponseT, ReturnT>)
          new SuspendForResponse<>(
              requestFactory,
              callFactory,
              responseConverter,
              (retrofit2.CallAdapter<ResponseT, retrofit2.Call<ResponseT>>) callAdapter);
    } else {
      //noinspection unchecked Kotlin compiler guarantees ReturnT to be Object.
      return (HttpServiceMethod<ResponseT, ReturnT>)
          new SuspendForBody<>(
              requestFactory,
              callFactory,
              responseConverter,
              (retrofit2.CallAdapter<ResponseT, retrofit2.Call<ResponseT>>) callAdapter,
              continuationBodyNullable,
              continuationIsUnit);
    }
  }

  private static <ResponseT, ReturnT> retrofit2.CallAdapter<ResponseT, ReturnT> createCallAdapter(
          retrofit2.Retrofit retrofit, Method method, Type returnType, Annotation[] annotations) {
    try {
      //noinspection unchecked
      return (retrofit2.CallAdapter<ResponseT, ReturnT>) retrofit.callAdapter(returnType, annotations);
    } catch (RuntimeException e) { // Wide exception range because factories are user code.
      throw methodError(method, e, "Unable to create call adapter for %s", returnType);
    }
  }

  private static <ResponseT> retrofit2.Converter<ResponseBody, ResponseT> createResponseConverter(
          Retrofit retrofit, Method method, Type responseType) {
    Annotation[] annotations = method.getAnnotations();
    try {
      return retrofit.responseBodyConverter(responseType, annotations);
    } catch (RuntimeException e) { // Wide exception range because factories are user code.
      throw methodError(method, e, "Unable to create converter for %s", responseType);
    }
  }

  private final retrofit2.RequestFactory requestFactory;
  private final okhttp3.Call.Factory callFactory;
  private final retrofit2.Converter<ResponseBody, ResponseT> responseConverter;

  HttpServiceMethod(
      retrofit2.RequestFactory requestFactory,
      okhttp3.Call.Factory callFactory,
      retrofit2.Converter<ResponseBody, ResponseT> responseConverter) {
    this.requestFactory = requestFactory;
    this.callFactory = callFactory;
    this.responseConverter = responseConverter;
  }

  @Override
  final @Nullable ReturnT invoke(Object[] args) {
    // 1.4
    // 看invoke的实现
    // adapt适配器，大致源码一般来说都不是核心，只是起到转换作用
    // 查看OkHttpCall源码，实现就是做okttp的网络请求
    retrofit2.Call<ResponseT> call = new retrofit2.OkHttpCall<>(requestFactory, args, callFactory, responseConverter);
    // 观察HttpServiceMethod的adapt调用的具体实现
    return adapt(call, args);
  }

  protected abstract @Nullable ReturnT adapt(retrofit2.Call<ResponseT> call, Object[] args);

  // 1.6 adapted的实现具体到底在做什么呢，追溯到这里
  static final class CallAdapted<ResponseT, ReturnT> extends HttpServiceMethod<ResponseT, ReturnT> {
    private final retrofit2.CallAdapter<ResponseT, ReturnT> callAdapter;

    CallAdapted(
        retrofit2.RequestFactory requestFactory,
        okhttp3.Call.Factory callFactory,
        retrofit2.Converter<ResponseBody, ResponseT> responseConverter,
        retrofit2.CallAdapter<ResponseT, ReturnT> callAdapter) {
      super(requestFactory, callFactory, responseConverter);
      this.callAdapter = callAdapter;
    }


    @Override
    protected ReturnT adapt(retrofit2.Call<ResponseT> call, Object[] args) {
      return callAdapter.adapt(call);
    }
  }

  static final class SuspendForResponse<ResponseT> extends HttpServiceMethod<ResponseT, Object> {
    private final retrofit2.CallAdapter<ResponseT, retrofit2.Call<ResponseT>> callAdapter;

    SuspendForResponse(
        retrofit2.RequestFactory requestFactory,
        okhttp3.Call.Factory callFactory,
        retrofit2.Converter<ResponseBody, ResponseT> responseConverter,
        retrofit2.CallAdapter<ResponseT, retrofit2.Call<ResponseT>> callAdapter) {
      super(requestFactory, callFactory, responseConverter);
      this.callAdapter = callAdapter;
    }

    @Override
    protected Object adapt(retrofit2.Call<ResponseT> call, Object[] args) {
      call = callAdapter.adapt(call);

      //noinspection unchecked Checked by reflection inside RequestFactory.
      Continuation<retrofit2.Response<ResponseT>> continuation =
          (Continuation<Response<ResponseT>>) args[args.length - 1];

      // See SuspendForBody for explanation about this try/catch.
      try {
        return KotlinExtensions.awaitResponse(call, continuation);
      } catch (Exception e) {
        return KotlinExtensions.suspendAndThrow(e, continuation);
      }
    }
  }

  static final class SuspendForBody<ResponseT> extends HttpServiceMethod<ResponseT, Object> {
    private final retrofit2.CallAdapter<ResponseT, retrofit2.Call<ResponseT>> callAdapter;
    private final boolean isNullable;
    private final boolean isUnit;

    SuspendForBody(
        retrofit2.RequestFactory requestFactory,
        okhttp3.Call.Factory callFactory,
        Converter<ResponseBody, ResponseT> responseConverter,
        CallAdapter<ResponseT, retrofit2.Call<ResponseT>> callAdapter,
        boolean isNullable,
        boolean isUnit) {
      super(requestFactory, callFactory, responseConverter);
      this.callAdapter = callAdapter;
      this.isNullable = isNullable;
      this.isUnit = isUnit;
    }

    @Override
    protected Object adapt(retrofit2.Call<ResponseT> call, Object[] args) {
      call = callAdapter.adapt(call);

      //noinspection unchecked Checked by reflection inside RequestFactory.
      Continuation<ResponseT> continuation = (Continuation<ResponseT>) args[args.length - 1];

      // Calls to OkHttp Call.enqueue() like those inside await and awaitNullable can sometimes
      // invoke the supplied callback with an exception before the invoking stack frame can return.
      // Coroutines will intercept the subsequent invocation of the Continuation and throw the
      // exception synchronously. A Java Proxy cannot throw checked exceptions without them being
      // declared on the interface method. To avoid the synchronous checked exception being wrapped
      // in an UndeclaredThrowableException, it is intercepted and supplied to a helper which will
      // force suspension to occur so that it can be instead delivered to the continuation to
      // bypass this restriction.
      try {
        if (isUnit) {
          //noinspection unchecked Checked by isUnit
          return KotlinExtensions.awaitUnit((Call<Unit>) call, (Continuation<Unit>) continuation);
        } else if (isNullable) {
          return KotlinExtensions.awaitNullable(call, continuation);
        } else {
          return KotlinExtensions.await(call, continuation);
        }
      } catch (Exception e) {
        return KotlinExtensions.suspendAndThrow(e, continuation);
      }
    }
  }
}
