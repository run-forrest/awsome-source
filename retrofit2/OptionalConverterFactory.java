/*
 * Copyright (C) 2017 Square, Inc.
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

import android.annotation.TargetApi;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import javax.annotation.Nullable;
import okhttp3.ResponseBody;
import retrofit2.Converter;
import retrofit2.Retrofit;

import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;

@IgnoreJRERequirement // Only added when Optional is available (Java 8+ / Android API 24+).
@TargetApi(24)
final class OptionalConverterFactory extends retrofit2.Converter.Factory {
  @Override
  public @Nullable
  retrofit2.Converter<ResponseBody, ?> responseBodyConverter(
      Type type, Annotation[] annotations, Retrofit retrofit) {
    if (getRawType(type) != Optional.class) {
      return null;
    }

    Type innerType = getParameterUpperBound(0, (ParameterizedType) type);
    retrofit2.Converter<ResponseBody, Object> delegate =
        retrofit.responseBodyConverter(innerType, annotations);
    return new OptionalConverter<>(delegate);
  }

  @IgnoreJRERequirement
  static final class OptionalConverter<T> implements retrofit2.Converter<ResponseBody, Optional<T>> {
    final retrofit2.Converter<ResponseBody, T> delegate;

    OptionalConverter(Converter<ResponseBody, T> delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<T> convert(ResponseBody value) throws IOException {
      return Optional.ofNullable(delegate.convert(value));
    }
  }
}
