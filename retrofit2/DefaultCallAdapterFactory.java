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

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import okhttp3.Request;
import okio.Timeout;
import retrofit2.Call;
import retrofit2.CallAdapter;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.SkipCallbackExecutor;
import retrofit2.Utils;

final class DefaultCallAdapterFactory extends retrofit2.CallAdapter.Factory {
  private final @Nullable Executor callbackExecutor;

  DefaultCallAdapterFactory(@Nullable Executor callbackExecutor) {
    this.callbackExecutor = callbackExecutor;
  }

  @Override
  public @Nullable
  retrofit2.CallAdapter<?, ?> get(
      Type returnType, Annotation[] annotations, Retrofit retrofit) {
    if (getRawType(returnType) != retrofit2.Call.class) {
      return null;
    }
    if (!(returnType instanceof ParameterizedType)) {
      throw new IllegalArgumentException(
          "Call return type must be parameterized as Call<Foo> or Call<? extends Foo>");
    }
    final Type responseType = retrofit2.Utils.getParameterUpperBound(0, (ParameterizedType) returnType);

    final Executor executor =
        retrofit2.Utils.isAnnotationPresent(annotations, SkipCallbackExecutor.class)
            ? null
            : callbackExecutor;

    return new CallAdapter<Object, retrofit2.Call<?>>() {
      @Override
      public Type responseType() {
        return responseType;
      }

      @Override
      public retrofit2.Call<Object> adapt(retrofit2.Call<Object> call) {
        // 追踪下去，adapte里面的call到底是什么，是ExecutorCallbackCall
        return executor == null ? call : new ExecutorCallbackCall<>(executor, call);
      }
    };
  }

  // 这里其实就是包了一层
  static final class ExecutorCallbackCall<T> implements retrofit2.Call<T> {
    // 线程工具
    final Executor callbackExecutor;
    final retrofit2.Call<T> delegate;

    ExecutorCallbackCall(Executor callbackExecutor, retrofit2.Call<T> delegate) {
      this.callbackExecutor = callbackExecutor;
      this.delegate = delegate;
    }

    @Override
    public void enqueue(final retrofit2.Callback<T> callback) {
      Objects.requireNonNull(callback, "callback == null");

      delegate.enqueue(
          new Callback<T>() {
            @Override
            public void onResponse(retrofit2.Call<T> call, final retrofit2.Response<T> response) {
              // 实际指引的就是这个方法执行的call
              // okhttp切过线程了，这里再切线程，其实最终最终
              // 就是使用handler使用Looper.getMainLooper()，切回主线程
              callbackExecutor.execute(
                  () -> {
                    if (delegate.isCanceled()) {

                      callback.onFailure(ExecutorCallbackCall.this, new IOException("Canceled"));
                    } else {
                      callback.onResponse(ExecutorCallbackCall.this, response);
                    }
                  });
            }

            @Override
            public void onFailure(retrofit2.Call<T> call, final Throwable t) {
              callbackExecutor.execute(() -> callback.onFailure(ExecutorCallbackCall.this, t));
            }
          });
    }

    @Override
    public boolean isExecuted() {
      return delegate.isExecuted();
    }

    @Override
    public Response<T> execute() throws IOException {
      return delegate.execute();
    }

    @Override
    public void cancel() {
      delegate.cancel();
    }

    @Override
    public boolean isCanceled() {
      return delegate.isCanceled();
    }

    @SuppressWarnings("CloneDoesntCallSuperClone") // Performing deep clone.
    @Override
    public Call<T> clone() {
      return new ExecutorCallbackCall<>(callbackExecutor, delegate.clone());
    }

    @Override
    public Request request() {
      return delegate.request();
    }

    @Override
    public Timeout timeout() {
      return delegate.timeout();
    }
  }
}
