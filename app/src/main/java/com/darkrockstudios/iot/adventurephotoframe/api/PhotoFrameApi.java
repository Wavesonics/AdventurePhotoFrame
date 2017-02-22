package com.darkrockstudios.iot.adventurephotoframe.api;

import com.darkrockstudios.iot.adventurephotoframe.data.Photo;

import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * Created by adamw on 12/21/2016.
 */

public interface PhotoFrameApi
{
	@GET("photoframe/{photoFrameId}")
	Call<Photo> getPhoto( @Path("photoFrameId") long photoFrameId );

	@FormUrlEncoded
	@POST("health/{photoFrameId}")
	Call<Void> healthCheckIn( @Path("photoFrameId") long photoFrameId, @Query("version") int version, @Field("errors") String errorLog );
}