/*
 * Copyright (C) 2019 Square, Inc.
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

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Type;

import retrofit2.Call;
import retrofit2.CallAdapter;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;

/**
 * Change the behavior of a {@code Call<BodyType>} return type to not use the {@linkplain
 * retrofit2.Retrofit#callbackExecutor() callback executor} for invoking the {@link retrofit2.Callback#onResponse(retrofit2.Call,
 * Response) onResponse} or {@link Callback#onFailure(retrofit2.Call, Throwable) onFailure} methods.
 *
 * <pre><code>
 * &#64;SkipCallbackExecutor
 * &#64;GET("user/{id}/token")
 * Call&lt;String&gt; getToken(@Path("id") long id);
 * </code></pre>
 *
 * This annotation can also be used when a {@link retrofit2.CallAdapter.Factory} <em>explicitly</em> delegates
 * to the built-in factory for {@link retrofit2.Call} via {@link Retrofit#nextCallAdapter(retrofit2.CallAdapter.Factory,
 * Type, Annotation[])} in order for the returned {@link retrofit2.Call} to skip the executor. (Note: by
 * default, a {@link Call} supplied directly to a {@link CallAdapter} will already skip the callback
 * executor. The annotation is only useful when looking up the built-in adapter.)
 */
@Documented
@Target(METHOD)
@Retention(RUNTIME)
public @interface SkipCallbackExecutor {}
