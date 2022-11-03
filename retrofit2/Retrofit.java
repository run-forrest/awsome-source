/*
 * Copyright (C) 2012 Square, Inc.
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

import static java.util.Collections.unmodifiableList;

import com.ownbranch.retrofit2.api.TestApiService;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.CallAdapter;
import retrofit2.Callback;
import retrofit2.Converter;
import retrofit2.http.GET;
import retrofit2.http.HTTP;
import retrofit2.http.Header;
import retrofit2.http.Url;

/**
 * Retrofit adapts a Java interface to HTTP calls by using annotations on the declared methods to
 * define how requests are made. Create instances using {@linkplain Builder the builder} and pass
 * your interface to {@link #create} to generate an implementation.
 *
 * <p>For example,
 *
 * <pre><code>
 * Retrofit retrofit = new Retrofit.Builder()
 *     .baseUrl("https://api.example.com/")
 *     .addConverterFactory(GsonConverterFactory.create())
 *     .build();
 *
 * MyApi api = retrofit.create(MyApi.class);
 * Response&lt;User&gt; user = api.getUser().execute();
 * </code></pre>
 *
 * @author Bob Lee (bob@squareup.com)
 * @author Jake Wharton (jw@squareup.com)
 */
public final class Retrofit {
  private final Map<Method, ServiceMethod<?>> serviceMethodCache = new ConcurrentHashMap<>();

  final okhttp3.Call.Factory callFactory;
  final HttpUrl baseUrl;
  final List<Converter.Factory> converterFactories;
  final int defaultConverterFactoriesSize;
  final List<CallAdapter.Factory> callAdapterFactories;
  final int defaultCallAdapterFactoriesSize;
  final @Nullable Executor callbackExecutor;
  final boolean validateEagerly;

  Retrofit(
      okhttp3.Call.Factory callFactory,
      HttpUrl baseUrl,
      List<Converter.Factory> converterFactories,
      int defaultConverterFactoriesSize,
      List<CallAdapter.Factory> callAdapterFactories,
      int defaultCallAdapterFactoriesSize,
      @Nullable Executor callbackExecutor,
      boolean validateEagerly) {
    this.callFactory = callFactory;
    this.baseUrl = baseUrl;
    this.converterFactories = converterFactories; // Copy+unmodifiable at call site.
    this.defaultConverterFactoriesSize = defaultConverterFactoriesSize;
    this.callAdapterFactories = callAdapterFactories; // Copy+unmodifiable at call site.
    this.defaultCallAdapterFactoriesSize = defaultCallAdapterFactoriesSize;
    this.callbackExecutor = callbackExecutor;
    this.validateEagerly = validateEagerly;
  }

  /**
   * Create an implementation of the API endpoints defined by the {@code service} interface.
   *
   * <p>The relative path for a given method is obtained from an annotation on the method describing
   * the request type. The built-in methods are {@link GET GET}, {@link
   * retrofit2.http.PUT PUT}, {@link retrofit2.http.POST POST}, {@link retrofit2.http.PATCH PATCH},
   * {@link retrofit2.http.HEAD HEAD}, {@link retrofit2.http.DELETE DELETE} and {@link
   * retrofit2.http.OPTIONS OPTIONS}. You can use a custom HTTP method with {@link HTTP @HTTP}. For
   * a dynamic URL, omit the path on the annotation and annotate the first parameter with {@link
   * Url @Url}.
   *
   * <p>Method parameters can be used to replace parts of the URL by annotating them with {@link
   * retrofit2.http.Path @Path}. Replacement sections are denoted by an identifier surrounded by
   * curly braces (e.g., "{foo}"). To add items to the query string of a URL use {@link
   * retrofit2.http.Query @Query}.
   *
   * <p>The body of a request is denoted by the {@link retrofit2.http.Body @Body} annotation. The
   * object will be converted to request representation by one of the {@link Converter.Factory}
   * instances. A {@link RequestBody} can also be used for a raw representation.
   *
   * <p>Alternative request body formats are supported by method annotations and corresponding
   * parameter annotations:
   *
   * <ul>
   *   <li>{@link retrofit2.http.FormUrlEncoded @FormUrlEncoded} - Form-encoded data with key-value
   *       pairs specified by the {@link retrofit2.http.Field @Field} parameter annotation.
   *   <li>{@link retrofit2.http.Multipart @Multipart} - RFC 2388-compliant multipart data with
   *       parts specified by the {@link retrofit2.http.Part @Part} parameter annotation.
   * </ul>
   *
   * <p>Additional static headers can be added for an endpoint using the {@link
   * retrofit2.http.Headers @Headers} method annotation. For per-request control over a header
   * annotate a parameter with {@link Header @Header}.
   *
   * <p>By default, methods return a {@link retrofit2.Call} which represents the HTTP request. The generic
   * parameter of the call is the response body type and will be converted by one of the {@link
   * Converter.Factory} instances. {@link ResponseBody} can also be used for a raw representation.
   * {@link Void} can be used if you do not care about the body contents.
   *
   * <p>For example:
   *
   * <pre>
   * public interface CategoryService {
   *   &#64;POST("category/{cat}/")
   *   Call&lt;List&lt;Item&gt;&gt; categoryList(@Path("cat") String a, @Query("page") int b);
   * }
   * </pre>
   */
  @SuppressWarnings("unchecked") // Single-interface proxy creation guarded by parameter safety.
  //最核心模块
  public <T> T create(final Class<T> service) {
    validateServiceInterface(service);
    return (T)
            // 动态代理，类是在运行的时候创建的
        Proxy.newProxyInstance(
                // 临时创建一个类的api
            service.getClassLoader(),
            // 网络接口类
            new Class<?>[] {service},
                // 匿名类
            new InvocationHandler() {
              private final Object[] emptyArgs = new Object[0];

              @Override
              public @Nullable Object invoke(Object proxy, Method method, @Nullable Object[] args)
                  throws Throwable {
                // 1.1
                // 这部分代码的意思是，代理的是声明在接口里的方法，toString啊这种方法，该干嘛干嘛去
                if (method.getDeclaringClass() == Object.class) {
                  return method.invoke(this, args);
                }
                // 这是确保java8的方法，不理会
                args = args != null ? args : emptyArgs;
                retrofit2.Platform platform = retrofit2.Platform.get();
                return platform.isDefaultMethod(method)
                    ? platform.invokeDefaultMethod(method, service, proxy, args)
                    : loadServiceMethod(method).invoke(args);
                // loadServiceMethod 核心代码

              }
            }
            );
  }

  // 动态代理创建实际的操作,模拟
  class ProxyTestApi implements TestApiService {
    // 实际实现的方式
    InvocationHandler invocationHandler = new InvocationHandler() {
      private final Object[] emptyArgs = new Object[0];

      @Override
      public @Nullable
      Object invoke(Object proxy, Method method, @Nullable Object[] args)
              throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
          return method.invoke(this, args);
        }
        args = args != null ? args : emptyArgs;
        retrofit2.Platform platform = retrofit2.Platform.get();
        return platform.isDefaultMethod(method)
                ? platform.invokeDefaultMethod(method, service, proxy, args)
                : loadServiceMethod(method).invoke(args);
      }
    };


    @Override
    public com.ownbranch.retrofit2.Call<String> listRepos(String xxx) {
      // 那invoke里面到底做什么呢
      // 实际上代码就是实现了这样一种情况
      // 重点就是invoke里面到底做了什么，参加1.1
      Method method = TestApiService.class.getDeclaredMethod("listRepos",String.class);
      return invocationHandler.invoke(this,method,xxx);
    }
  }

  private void validateServiceInterface(Class<?> service) {
    if (!service.isInterface()) {
      throw new IllegalArgumentException("API declarations must be interfaces.");
    }

    Deque<Class<?>> check = new ArrayDeque<>(1);
    check.add(service);
    while (!check.isEmpty()) {
      Class<?> candidate = check.removeFirst();
      // 判断是否有泛型
      if (candidate.getTypeParameters().length != 0) {
        StringBuilder message =
            new StringBuilder("Type parameters are unsupported on ").append(candidate.getName());
        if (candidate != service) {
          message.append(" which is an interface of ").append(service.getName());
        }
        throw new IllegalArgumentException(message.toString());
      }
      Collections.addAll(check, candidate.getInterfaces());
    }

    // validateEagerly 是否激进验证
    // 原因是api接口初次调用的时候，会被初始化，初始化就是对方法的验证
    // 所以在retrofit.create的时候，会进行验证，验证api创建的时候，是否有问题
    // 初始化有反射，所以一开始就验证，有耗时风险
    if (validateEagerly) {
      retrofit2.Platform platform = retrofit2.Platform.get();
      for (Method method : service.getDeclaredMethods()) {
        // 是否是实现了默认方法，静态方法，Java8能支持的原因
        if (!platform.isDefaultMethod(method) && !Modifier.isStatic(method.getModifiers())) {
          loadServiceMethod(method);
        }
      }
    }
  }

  // 1.2
  // 核心实现
  // 带缓存的加载
  ServiceMethod<?> loadServiceMethod(Method method) {
    ServiceMethod<?> result = serviceMethodCache.get(method);
    if (result != null) return result;

    synchronized (serviceMethodCache) {
      result = serviceMethodCache.get(method);
      if (result == null) {
        // 指向的实现
        // parseAnnotations怎么返回指定对象？
        // invoke是怎么实现的
        // 其实本质上就是在里面做了解析注解的操作
        // 然后里面进行了okhttp的请求
        // 获取被代理的接口的方法注解
        //                        Annotation[] annotations = method.getDeclaredAnnotations();
        //                        for (Annotation annotation : annotations) {
        //                            //如果找到对应的Get注解，则将Get注解中的url和mBaseUrl拼接一起
        //                            if (annotation instanceof Get) {
        //                                String path = ((Get) annotation).value();
        //                                String url = mBaseUrl + "/" + path;
        //                                System.out.println("url:" + url);
        //                                //通过OkHttp请求url，并将结果回调到外部
        //                                final OkHttpClient okHttpClient = new OkHttpClient().newBuilder().build();
        //                                final Request request = new Request.Builder()
        //                                        .url(url)
        //                                        .build();
        //                                call = new Call<Object>() {
        //                                    @Override
        //                                    public void enqueue(final CallBack<Object> callBack) {
        //                                        okHttpClient.newCall(request).enqueue(new Callback() {
        //                                            @Override
        //                                            public void onFailure(@NotNull okhttp3.Call call, @NotNull IOException e) {
        //                                                callBack.onFail(e);
        //                                            }
        //
        //                                            @Override
        //                                            public void onResponse(@NotNull okhttp3.Call call, @NotNull Response response) throws IOException {
        //                                                callBack.onResponse(response.body().string());
        //                                            }
        //                                        });
        //
        //                                    }
        //                                };
        //
        //                            }
        //                        }
        //
        //                        return call;
        //  如同上述代码，就是这段封装最基础的实现，可以视为两者其实是一致的
        result = ServiceMethod.parseAnnotations(this, method);
        serviceMethodCache.put(method, result);
      }
    }
    return result;
  }

  /**
   * The factory used to create {@linkplain okhttp3.Call OkHttp calls} for sending a HTTP requests.
   * Typically an instance of {@link OkHttpClient}.
   */
  public okhttp3.Call.Factory callFactory() {
    return callFactory;
  }

  /** The API base URL. */
  public HttpUrl baseUrl() {
    return baseUrl;
  }

  /**
   * Returns a list of the factories tried when creating a {@linkplain #callAdapter(Type,
   * Annotation[])} call adapter}.
   */
  public List<CallAdapter.Factory> callAdapterFactories() {
    return callAdapterFactories;
  }

  /**
   * Returns the {@link CallAdapter} for {@code returnType} from the available {@linkplain
   * #callAdapterFactories() factories}.
   *
   * @throws IllegalArgumentException if no call adapter available for {@code type}.
   */
  public CallAdapter<?, ?> callAdapter(Type returnType, Annotation[] annotations) {
    return nextCallAdapter(null, returnType, annotations);
  }

  /**
   * Returns the {@link CallAdapter} for {@code returnType} from the available {@linkplain
   * #callAdapterFactories() factories} except {@code skipPast}.
   *
   * @throws IllegalArgumentException if no call adapter available for {@code type}.
   */
  public CallAdapter<?, ?> nextCallAdapter(
      @Nullable CallAdapter.Factory skipPast, Type returnType, Annotation[] annotations) {
    Objects.requireNonNull(returnType, "returnType == null");
    Objects.requireNonNull(annotations, "annotations == null");
    // 取值而已，所以要找到塞入的地方，需要查看callAdapterFactories初始化的地方
    int start = callAdapterFactories.indexOf(skipPast) + 1;
    for (int i = start, count = callAdapterFactories.size(); i < count; i++) {
      CallAdapter<?, ?> adapter = callAdapterFactories.get(i).get(returnType, annotations, this);
      if (adapter != null) {
        return adapter;
      }
    }

    StringBuilder builder =
        new StringBuilder("Could not locate call adapter for ").append(returnType).append(".\n");
    if (skipPast != null) {
      builder.append("  Skipped:");
      for (int i = 0; i < start; i++) {
        builder.append("\n   * ").append(callAdapterFactories.get(i).getClass().getName());
      }
      builder.append('\n');
    }
    builder.append("  Tried:");
    for (int i = start, count = callAdapterFactories.size(); i < count; i++) {
      builder.append("\n   * ").append(callAdapterFactories.get(i).getClass().getName());
    }
    throw new IllegalArgumentException(builder.toString());
  }

  /**
   * Returns an unmodifiable list of the factories tried when creating a {@linkplain
   * #requestBodyConverter(Type, Annotation[], Annotation[]) request body converter}, a {@linkplain
   * #responseBodyConverter(Type, Annotation[]) response body converter}, or a {@linkplain
   * #stringConverter(Type, Annotation[]) string converter}.
   */
  public List<Converter.Factory> converterFactories() {
    return converterFactories;
  }

  /**
   * Returns a {@link Converter} for {@code type} to {@link RequestBody} from the available
   * {@linkplain #converterFactories() factories}.
   *
   * @throws IllegalArgumentException if no converter available for {@code type}.
   */
  public <T> Converter<T, RequestBody> requestBodyConverter(
      Type type, Annotation[] parameterAnnotations, Annotation[] methodAnnotations) {
    return nextRequestBodyConverter(null, type, parameterAnnotations, methodAnnotations);
  }

  /**
   * Returns a {@link Converter} for {@code type} to {@link RequestBody} from the available
   * {@linkplain #converterFactories() factories} except {@code skipPast}.
   *
   * @throws IllegalArgumentException if no converter available for {@code type}.
   */
  public <T> Converter<T, RequestBody> nextRequestBodyConverter(
      @Nullable Converter.Factory skipPast,
      Type type,
      Annotation[] parameterAnnotations,
      Annotation[] methodAnnotations) {
    Objects.requireNonNull(type, "type == null");
    Objects.requireNonNull(parameterAnnotations, "parameterAnnotations == null");
    Objects.requireNonNull(methodAnnotations, "methodAnnotations == null");

    int start = converterFactories.indexOf(skipPast) + 1;
    for (int i = start, count = converterFactories.size(); i < count; i++) {
      Converter.Factory factory = converterFactories.get(i);
      Converter<?, RequestBody> converter =
          factory.requestBodyConverter(type, parameterAnnotations, methodAnnotations, this);
      if (converter != null) {
        //noinspection unchecked
        return (Converter<T, RequestBody>) converter;
      }
    }

    StringBuilder builder =
        new StringBuilder("Could not locate RequestBody converter for ").append(type).append(".\n");
    if (skipPast != null) {
      builder.append("  Skipped:");
      for (int i = 0; i < start; i++) {
        builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
      }
      builder.append('\n');
    }
    builder.append("  Tried:");
    for (int i = start, count = converterFactories.size(); i < count; i++) {
      builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
    }
    throw new IllegalArgumentException(builder.toString());
  }

  /**
   * Returns a {@link Converter} for {@link ResponseBody} to {@code type} from the available
   * {@linkplain #converterFactories() factories}.
   *
   * @throws IllegalArgumentException if no converter available for {@code type}.
   */
  public <T> Converter<ResponseBody, T> responseBodyConverter(Type type, Annotation[] annotations) {
    return nextResponseBodyConverter(null, type, annotations);
  }

  /**
   * Returns a {@link Converter} for {@link ResponseBody} to {@code type} from the available
   * {@linkplain #converterFactories() factories} except {@code skipPast}.
   *
   * @throws IllegalArgumentException if no converter available for {@code type}.
   */
  public <T> Converter<ResponseBody, T> nextResponseBodyConverter(
      @Nullable Converter.Factory skipPast, Type type, Annotation[] annotations) {
    Objects.requireNonNull(type, "type == null");
    Objects.requireNonNull(annotations, "annotations == null");

    int start = converterFactories.indexOf(skipPast) + 1;
    for (int i = start, count = converterFactories.size(); i < count; i++) {
      Converter<ResponseBody, ?> converter =
          converterFactories.get(i).responseBodyConverter(type, annotations, this);
      if (converter != null) {
        //noinspection unchecked
        return (Converter<ResponseBody, T>) converter;
      }
    }

    StringBuilder builder =
        new StringBuilder("Could not locate ResponseBody converter for ")
            .append(type)
            .append(".\n");
    if (skipPast != null) {
      builder.append("  Skipped:");
      for (int i = 0; i < start; i++) {
        builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
      }
      builder.append('\n');
    }
    builder.append("  Tried:");
    for (int i = start, count = converterFactories.size(); i < count; i++) {
      builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
    }
    throw new IllegalArgumentException(builder.toString());
  }

  /**
   * Returns a {@link Converter} for {@code type} to {@link String} from the available {@linkplain
   * #converterFactories() factories}.
   */
  public <T> Converter<T, String> stringConverter(Type type, Annotation[] annotations) {
    Objects.requireNonNull(type, "type == null");
    Objects.requireNonNull(annotations, "annotations == null");

    for (int i = 0, count = converterFactories.size(); i < count; i++) {
      Converter<?, String> converter =
          converterFactories.get(i).stringConverter(type, annotations, this);
      if (converter != null) {
        //noinspection unchecked
        return (Converter<T, String>) converter;
      }
    }

    // Nothing matched. Resort to default converter which just calls toString().
    //noinspection unchecked
    return (Converter<T, String>) BuiltInConverters.ToStringConverter.INSTANCE;
  }

  /**
   * The executor used for {@link retrofit2.Callback} methods on a {@link retrofit2.Call}. This may be {@code null}, in
   * which case callbacks should be made synchronously on the background thread.
   */
  public @Nullable Executor callbackExecutor() {
    return callbackExecutor;
  }

  public Builder newBuilder() {
    return new Builder(this);
  }

  /**
   * Build a new {@link Retrofit}.
   *
   * <p>Calling {@link #baseUrl} is required before calling {@link #build()}. All other methods are
   * optional.
   */
  public static final class Builder {
    private @Nullable okhttp3.Call.Factory callFactory;
    private @Nullable HttpUrl baseUrl;
    private final List<Converter.Factory> converterFactories = new ArrayList<>();
    private final List<CallAdapter.Factory> callAdapterFactories = new ArrayList<>();
    private @Nullable Executor callbackExecutor;
    private boolean validateEagerly;

    public Builder() {}

    Builder(Retrofit retrofit) {
      callFactory = retrofit.callFactory;
      baseUrl = retrofit.baseUrl;

      // Do not add the default BuiltIntConverters and platform-aware converters added by build().
      for (int i = 1,
              size = retrofit.converterFactories.size() - retrofit.defaultConverterFactoriesSize;
          i < size;
          i++) {
        converterFactories.add(retrofit.converterFactories.get(i));
      }

      // Do not add the default, platform-aware call adapters added by build().
      for (int i = 0,
              size =
                  retrofit.callAdapterFactories.size() - retrofit.defaultCallAdapterFactoriesSize;
          i < size;
          i++) {
        callAdapterFactories.add(retrofit.callAdapterFactories.get(i));
      }

      callbackExecutor = retrofit.callbackExecutor;
      validateEagerly = retrofit.validateEagerly;
    }

    /**
     * The HTTP client used for requests.
     *
     * <p>This is a convenience method for calling {@link #callFactory}.
     */
    public Builder client(OkHttpClient client) {
      return callFactory(Objects.requireNonNull(client, "client == null"));
    }

    /**
     * Specify a custom call factory for creating {@link retrofit2.Call} instances.
     *
     * <p>Note: Calling {@link #client} automatically sets this value.
     */
    public Builder callFactory(okhttp3.Call.Factory factory) {
      this.callFactory = Objects.requireNonNull(factory, "factory == null");
      return this;
    }

    /**
     * Set the API base URL.
     *
     * @see #baseUrl(HttpUrl)
     */
    public Builder baseUrl(URL baseUrl) {
      Objects.requireNonNull(baseUrl, "baseUrl == null");
      return baseUrl(HttpUrl.get(baseUrl.toString()));
    }

    /**
     * Set the API base URL.
     *
     * @see #baseUrl(HttpUrl)
     */
    public Builder baseUrl(String baseUrl) {
      Objects.requireNonNull(baseUrl, "baseUrl == null");
      return baseUrl(HttpUrl.get(baseUrl));
    }

    /**
     * Set the API base URL.
     *
     * <p>The specified endpoint values (such as with {@link GET @GET}) are resolved against this
     * value using {@link HttpUrl#resolve(String)}. The behavior of this matches that of an {@code
     * <a href="">} link on a website resolving on the current URL.
     *
     * <p><b>Base URLs should always end in {@code /}.</b>
     *
     * <p>A trailing {@code /} ensures that endpoints values which are relative paths will correctly
     * append themselves to a base which has path components.
     *
     * <p><b>Correct:</b><br>
     * Base URL: http://example.com/api/<br>
     * Endpoint: foo/bar/<br>
     * Result: http://example.com/api/foo/bar/
     *
     * <p><b>Incorrect:</b><br>
     * Base URL: http://example.com/api<br>
     * Endpoint: foo/bar/<br>
     * Result: http://example.com/foo/bar/
     *
     * <p>This method enforces that {@code baseUrl} has a trailing {@code /}.
     *
     * <p><b>Endpoint values which contain a leading {@code /} are absolute.</b>
     *
     * <p>Absolute values retain only the host from {@code baseUrl} and ignore any specified path
     * components.
     *
     * <p>Base URL: http://example.com/api/<br>
     * Endpoint: /foo/bar/<br>
     * Result: http://example.com/foo/bar/
     *
     * <p>Base URL: http://example.com/<br>
     * Endpoint: /foo/bar/<br>
     * Result: http://example.com/foo/bar/
     *
     * <p><b>Endpoint values may be a full URL.</b>
     *
     * <p>Values which have a host replace the host of {@code baseUrl} and values also with a scheme
     * replace the scheme of {@code baseUrl}.
     *
     * <p>Base URL: http://example.com/<br>
     * Endpoint: https://github.com/square/retrofit/<br>
     * Result: https://github.com/square/retrofit/
     *
     * <p>Base URL: http://example.com<br>
     * Endpoint: //github.com/square/retrofit/<br>
     * Result: http://github.com/square/retrofit/ (note the scheme stays 'http')
     */
    public Builder baseUrl(HttpUrl baseUrl) {
      Objects.requireNonNull(baseUrl, "baseUrl == null");
      List<String> pathSegments = baseUrl.pathSegments();
      if (!"".equals(pathSegments.get(pathSegments.size() - 1))) {
        throw new IllegalArgumentException("baseUrl must end in /: " + baseUrl);
      }
      this.baseUrl = baseUrl;
      return this;
    }

    /** Add converter factory for serialization and deserialization of objects. */
    public Builder addConverterFactory(Converter.Factory factory) {
      converterFactories.add(Objects.requireNonNull(factory, "factory == null"));
      return this;
    }

    /**
     * Add a call adapter factory for supporting service method return types other than {@link
     * retrofit2.Call}.
     */
    public Builder addCallAdapterFactory(CallAdapter.Factory factory) {
      callAdapterFactories.add(Objects.requireNonNull(factory, "factory == null"));
      return this;
    }

    /**
     * The executor on which {@link Callback} methods are invoked when returning {@link Call} from
     * your service method.
     *
     * <p>Note: {@code executor} is not used for {@linkplain #addCallAdapterFactory custom method
     * return types}.
     */
    public Builder callbackExecutor(Executor executor) {
      this.callbackExecutor = Objects.requireNonNull(executor, "executor == null");
      return this;
    }

    /** Returns a modifiable list of call adapter factories. */
    public List<CallAdapter.Factory> callAdapterFactories() {
      return this.callAdapterFactories;
    }

    /** Returns a modifiable list of converter factories. */
    public List<Converter.Factory> converterFactories() {
      return this.converterFactories;
    }

    /**
     * When calling {@link #create} on the resulting {@link Retrofit} instance, eagerly validate the
     * configuration of all methods in the supplied interface.
     */
    public Builder validateEagerly(boolean validateEagerly) {
      this.validateEagerly = validateEagerly;
      return this;
    }

    /**
     * Create the {@link Retrofit} instance using the configured values.
     *
     * <p>Note: If neither {@link #client} nor {@link #callFactory} is called a default {@link
     * OkHttpClient} will be created and used.
     */
    public Retrofit build() {
      if (baseUrl == null) {
        throw new IllegalStateException("Base URL required.");
      }

      retrofit2.Platform platform = retrofit2.Platform.get();

      okhttp3.Call.Factory callFactory = this.callFactory;
      if (callFactory == null) {
        callFactory = new OkHttpClient();
      }

      Executor callbackExecutor = this.callbackExecutor;
      if (callbackExecutor == null) {
        callbackExecutor = platform.defaultCallbackExecutor();
      }

      // Make a defensive copy of the adapters and add the default Call adapter.
      List<CallAdapter.Factory> callAdapterFactories = new ArrayList<>(this.callAdapterFactories);
      List<? extends CallAdapter.Factory> defaultCallAdapterFactories =
          platform.createDefaultCallAdapterFactories(callbackExecutor);
      callAdapterFactories.addAll(defaultCallAdapterFactories);

      // Make a defensive copy of the converters.
      List<? extends Converter.Factory> defaultConverterFactories =
          platform.createDefaultConverterFactories();
      int defaultConverterFactoriesSize = defaultConverterFactories.size();
      List<Converter.Factory> converterFactories =
          new ArrayList<>(1 + this.converterFactories.size() + defaultConverterFactoriesSize);

      // Add the built-in converter factory first. This prevents overriding its behavior but also
      // ensures correct behavior when using converters that consume all types.
      converterFactories.add(new BuiltInConverters());
      converterFactories.addAll(this.converterFactories);
      converterFactories.addAll(defaultConverterFactories);

      return new Retrofit(
          callFactory,
          baseUrl,
          unmodifiableList(converterFactories),
          defaultConverterFactoriesSize,
          unmodifiableList(callAdapterFactories),
          defaultCallAdapterFactories.size(),
          callbackExecutor,
          validateEagerly);
    }
  }
}
