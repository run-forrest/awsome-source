package com.ownbranch.retrofit2.api;


import com.ownbranch.retrofit2.Call;
import com.ownbranch.retrofit2.http.GET;
import com.ownbranch.retrofit2.http.Path;

public interface TestApiService {
    @GET("")
    Call<String> listRepos(@Path("xxx") String xxx);
}
