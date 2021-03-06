package com.darkrockstudios.iot.adventurephotoframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.View
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.darkrockstudios.iot.adventurephotoframe.application.App
import com.darkrockstudios.iot.adventurephotoframe.application.GlideApp
import com.darkrockstudios.iot.adventurephotoframe.base.BaseActivity
import com.darkrockstudios.iot.adventurephotoframe.data.Photo
import com.darkrockstudios.iot.adventurephotoframe.settings.Settings
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.tutorial.*
import kotlinx.android.synthetic.main.tutorial_hotspots.*
import me.eugeniomarletti.extras.SimpleActivityCompanion
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response


class MainActivity : BaseActivity()
{
	companion object : SimpleActivityCompanion(MainActivity::class)
	{
		private val TAG = MainActivity::class.java.simpleName

		private val FAILURE_BACKOFF = 1L * 60L * 1000L
	}

	private var m_currentPhoto: Photo? = null

	private val m_handler: Handler = Handler()
	private val m_updateTask: UpdatePhotoTask = UpdatePhotoTask()

	private var m_updateFrequency: Long = 0

	private val m_connectionListener: ConnectionStateReceiver? = ConnectionStateReceiver()

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		m_updateFrequency = Settings.getUpdateFrequency(this)

		TUTORIAL_photo_info.setOnClickListener(this::onPhotoInfo)
		TUTORIAL_hotspots_container.setOnClickListener(this::onPhotoInfo)

		TUTORIAL_tutorial.setOnClickListener(this::onTutorial)
		TUTORIAL_tutorial_hotspot.setOnClickListener(this::onTutorial)

		TUTORIAL_next_photo.setOnClickListener(this::onNext)
		TUTORIAL_next_photo_hotspot.setOnClickListener(this::onNext)

		TUTORIAL_settings.setOnClickListener(this::onSettings)
		TUTORIAL_settings_hotspot.setOnClickListener(this::onSettings)

		TUTORIAL_about.setOnClickListener(this::onAbout)
		TUTORIAL_about_hotspot.setOnClickListener(this::onAbout)

		handleFirstRun()
	}

	private fun handleFirstRun()
	{
		if (Settings.getFirstRun(this))
		{
			WelcomeActivity.start(this)
		}
	}

	override fun onStart()
	{
		super.onStart()

		val intentFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
		registerReceiver(m_connectionListener, intentFilter)
	}

	override fun onResume()
	{
		super.onResume()
		m_updateFrequency = Settings.getUpdateFrequency(this)

		scheduleUpdateTask(m_updateFrequency)

		updateConnectionStatus()

		if (m_currentPhoto == null)
		{
			requestNewPhoto()
		}
	}

	override fun onPause()
	{
		super.onPause()
		m_handler.removeCallbacks(m_updateTask)
	}

	override fun onStop()
	{
		super.onStop()

		unregisterReceiver(m_connectionListener)
	}

	override val contentLayout: Int
		get() = R.layout.activity_main

	private fun scheduleUpdateTask(delay: Long)
	{
		m_handler.removeCallbacks(m_updateTask)
		m_handler.postDelayed(m_updateTask, delay)
	}

	private fun requestNewPhoto()
	{
		m_handler.removeCallbacks(m_updateTask)

		hideTutorial()
		PHOTOFRAME_loading.visibility = View.VISIBLE

		val call = App.inst.networking.m_photoFrameService.getPhoto(App.inst.deviceId)
		Log.d(TAG, "Requesting photo info...")
		call.enqueue(PhotoCallback())
	}

	private fun updatePhoto(photo: Photo)
	{
		m_currentPhoto = photo

		val photoView = PHOTOFRAME_photo
		if (photoView != null)
		{
			Log.i(TAG, "Loading image: " + photo.image)

			GlideApp.with(this)
					.load(photo.image)
					.placeholder(R.drawable.loading)
					.error(R.drawable.no_image)
					.transition(DrawableTransitionOptions.withCrossFade())
					.listener(ImageCallback(photo.id))
					.into(photoView)
		}
		else
		{
			Log.i(TAG, "Can't load image, View is not ready.")
		}
	}

	private fun updateConnectionStatus()
	{
		val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

		val activeNetwork = cm.activeNetworkInfo
		val isConnected = activeNetwork != null && activeNetwork.isConnected

		PHOTOFRAME_wifi_down.visibility = if (isConnected) View.GONE else View.VISIBLE
	}

	private fun showTutorial()
	{
		TUTORIAL_hotspots_container.visibility = View.GONE
		TUTORIAL_container.visibility = View.VISIBLE
		TUTORIAL_container.alpha = 1.0f

		/*
		ObjectAnimator anim = ObjectAnimator.ofFloat( TUTORIAL_container, "alpha", 0f, 1f );
		anim.setDuration( 50 );
		anim.start();
		*/
	}

	private fun hideTutorial()
	{
		TUTORIAL_hotspots_container.visibility = View.VISIBLE
		TUTORIAL_container.visibility = View.GONE
		TUTORIAL_container.alpha = 0.0f
		/*
		ObjectAnimator anim = ObjectAnimator.ofFloat( TUTORIAL_container, "alpha", 1f, 0f );
		anim.setDuration( 50 );
		anim.start();
		*/
	}

	private val isTutorialShowing: Boolean
		get() = TUTORIAL_container.alpha == 1.0f

	private inner class ImageCallback(val imageId: String?) : RequestListener<Drawable>
	{
		override fun onResourceReady(resource: Drawable?, model: Any?, target: Target<Drawable>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean
		{
			imageId?.let { logPhotoSuccess(imageId) }

			Log.i(TAG, "Image load successful!")
			PHOTOFRAME_loading.visibility = View.GONE

			scheduleUpdateTask(m_updateFrequency)

			return false
		}

		override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean
		{
			imageId?.let { logPhotoFailure(imageId) }

			Log.w(TAG, "Image load failed.")
			PHOTOFRAME_loading.visibility = View.GONE

			scheduleUpdateTask(FAILURE_BACKOFF)

			return false
		}
	}

	private inner class PhotoCallback : Callback<Photo>
	{
		override fun onResponse(call: Call<Photo>, response: Response<Photo>)
		{
			Log.d(TAG, "Got photo info...")
			val photo = response.body()
			if (photo != null)
			{
				photo.id?.let { logPhotoInfoSuccess(photo.id) }

				Log.d(TAG, photo.toString())
				updatePhoto(photo)
			}
			else
			{
				logPhotoInfoFailure()

				Log.w(TAG, "Failed to get photo info from success")
				PHOTOFRAME_loading.visibility = View.GONE
				scheduleUpdateTask(FAILURE_BACKOFF)
			}
		}

		override fun onFailure(call: Call<Photo>, t: Throwable)
		{
			logPhotoInfoFailure()

			Log.w(TAG, "Failed to get photo info... " + t)
			PHOTOFRAME_loading.visibility = View.GONE
			scheduleUpdateTask(FAILURE_BACKOFF)
		}
	}

	private fun onTutorial(view: View)
	{
		if (isTutorialShowing)
		{
			hideTutorial()
		}
		else
		{
			showTutorial()
		}
	}

	private fun onNext(view: View)
	{
		hideTutorial()
		requestNewPhoto()
	}

	private fun onPhotoInfo(view: View)
	{
		m_currentPhoto?.id?.let { logPhotoDetails(it) }

		hideTutorial()

		if (m_currentPhoto != null)
		{
			PhotoInfoActivity.start(this) {
				it.photoInfo = m_currentPhoto
			}
		}
		else
		{
			requestNewPhoto()
		}
	}

	private fun onSettings(view: View)
	{
		hideTutorial()
		SettingsActivity.start(this)
	}

	private fun onAbout(view: View)
	{
		hideTutorial()
		AboutActivity.start(this)
	}

	private inner class UpdatePhotoTask : Runnable
	{
		override fun run()
		{
			requestNewPhoto()
		}
	}

	private inner class ConnectionStateReceiver : BroadcastReceiver()
	{
		override fun onReceive(context: Context, intent: Intent)
		{
			updateConnectionStatus()
		}
	}

	private fun logPhotoInfoFailure()
	{
		App.inst.analytics.logEvent("photo_info_failure", Bundle())
	}

	private val PARAM_VERB = "verb"
	private fun logPhotoInfoSuccess(id: String)
	{
		val bundle = Bundle()
		bundle.putString(FirebaseAnalytics.Param.ITEM_ID, id)
		bundle.putString(PARAM_VERB, "photo_info_success")
		App.inst.analytics.logEvent(FirebaseAnalytics.Event.VIEW_ITEM, bundle)
	}

	private fun logPhotoSuccess(id: String)
	{
		val bundle = Bundle()
		bundle.putString(FirebaseAnalytics.Param.ITEM_ID, id)
		bundle.putString(PARAM_VERB, "photo_load_success")
		App.inst.analytics.logEvent(FirebaseAnalytics.Event.VIEW_ITEM, bundle)
	}

	private fun logPhotoFailure(id: String)
	{
		val bundle = Bundle()
		bundle.putString(FirebaseAnalytics.Param.ITEM_ID, id)
		bundle.putString(PARAM_VERB, "photo_load_failure")
		App.inst.analytics.logEvent(FirebaseAnalytics.Event.VIEW_ITEM, bundle)
	}

	private fun logPhotoDetails(id: String)
	{
		val bundle = Bundle()
		bundle.putString(FirebaseAnalytics.Param.ITEM_ID, id)
		bundle.putString(PARAM_VERB, "photo_details")
		App.inst.analytics.logEvent(FirebaseAnalytics.Event.VIEW_ITEM, bundle)
	}
}
