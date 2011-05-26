package org.aprsdroid.app

import _root_.android.app.Service
import _root_.android.content.{Context, Intent}
import _root_.android.location._
import _root_.android.os.{Bundle, IBinder, Handler}
import _root_.android.preference.PreferenceManager
import _root_.android.util.Log
import _root_.android.widget.Toast

object AprsService {
	val PACKAGE = "org.aprsdroid.app"
	val SERVICE = PACKAGE + ".SERVICE"
	val SERVICE_ONCE = PACKAGE + ".ONCE"
	val UPDATE = PACKAGE + ".UPDATE"
	val LOCATION = PACKAGE + ".LOCATION"
	val STATUS = PACKAGE + ".STATUS"
	val PACKET = PACKAGE + ".PACKET"

	val FAST_LANE_ACT = 30000

	def intent(ctx : Context, action : String) : Intent = {
		new Intent(action, null, ctx, classOf[AprsService])
	}

	var running = false

	implicit def block2runnable(block: => Unit) =
		new Runnable() {
			def run() { block }
		}

}

class AprsService extends Service with LocationListener {
	import AprsService._
	val TAG = "AprsService"

	lazy val prefs = new PrefsWrapper(this)

	lazy val locMan = getSystemService(Context.LOCATION_SERVICE).asInstanceOf[LocationManager]

	val handler = new Handler()

	lazy val db = StorageDatabase.open(this)

	var poster : AprsIsUploader = null

	var singleShot = false
	var lastLoc : Location = null
	var fastLaneLoc : Location = null

	override def onStart(i : Intent, startId : Int) {
		Log.d(TAG, "onStart: " + i + ", " + startId);
		super.onStart(i, startId)
		handleStart(i)
	}

	override def onStartCommand(i : Intent, flags : Int, startId : Int) : Int = {
		Log.d(TAG, "onStartCommand: " + i + ", " + flags + ", " + startId);
		handleStart(i)
		Service.START_REDELIVER_INTENT
	}

	def requestLocations(stay_on : Boolean) {
		// get update interval and distance
		val upd_int = prefs.getStringInt("interval", 10)
		val upd_dist = prefs.getStringInt("distance", 10)
		val gps_act = prefs.getString("gps_activation", "med")
		if (stay_on || (gps_act == "always")) {
			locMan.requestLocationUpdates(LocationManager.GPS_PROVIDER,
				0, 0, this)
		} else {
			// for GPS precision == medium, we use getGpsInterval()
			locMan.requestLocationUpdates(LocationManager.GPS_PROVIDER,
				upd_int * 60000 - getGpsInterval(), upd_dist * 1000, this)
		}
		if (prefs.getBoolean("netloc", false)) {
			locMan.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
				upd_int * 60000, upd_dist * 1000, this)
		}
	}

	def handleStart(i : Intent) {
		// get update interval and distance
		val upd_int = prefs.getStringInt("interval", 10)
		val upd_dist = prefs.getStringInt("distance", 10)

		// display notification (even though we are not actually started yet,
		// but we need this to prevent error message reordering)
		fastLaneLoc = null
		if (i.getAction() == SERVICE_ONCE) {
			// if already running, we want to send immediately and continue;
			// otherwise, we finish after a single position report
			lastLoc = null
			// set to true if not yet running or already running singleShot
			singleShot = !running || singleShot
			if (singleShot)
					showToast(getString(R.string.service_once))
		} else
			showToast(getString(R.string.service_start).format(upd_int, upd_dist))

		// the poster needs to be running before location updates come in
		if (!running) {
			running = true
			startPoster()
		}

		// continuous GPS tracking for single shot mode
		requestLocations(singleShot)

		val callssid = prefs.getCallSsid()
		val message = "%s: %d min, %d km".format(callssid, upd_int, upd_dist)
		ServiceNotifier.instance.start(this, message)
	}

	def startPoster() {
		if (poster != null)
			poster.stop()
		poster = AprsIsUploader.instanciateUploader(this, prefs)
		poster.start()
	}

	override def onBind(i : Intent) : IBinder = null
		
	override def onUnbind(i : Intent) : Boolean = false
		
	def showToast(msg : String) {
		Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
		addPost(StorageDatabase.Post.TYPE_INFO, null, msg)
	}

	override def onDestroy() {
		locMan.removeUpdates(this);
		// catch FC when service is killed from outside
		if (poster != null) {
			poster.stop()
			showToast(getString(R.string.service_stop))
		}
		ServiceNotifier.instance.stop(this)
		running = false
	}

	def getGpsInterval() : Int = {
		val gps_act = prefs.getString("gps_activation", "med")
		if (gps_act == "med") FAST_LANE_ACT
				else  0
	}

	def startFastLane() {
		Log.d(TAG, "switching to fast lane");
		// request fast update rate
		locMan.removeUpdates(this);
		requestLocations(true)
		handler.postDelayed({ stopFastLane(true) }, FAST_LANE_ACT)
	}

	def stopFastLane(post : Boolean) {
		if (!running)
			return;
		Log.d(TAG, "switching to slow lane");
		if (post && fastLaneLoc != null) {
			Log.d(TAG, "stopFastLane: posting " + fastLaneLoc);
			postLocation(fastLaneLoc)
		}
		fastLaneLoc = null
		// reset update speed
		locMan.removeUpdates(this);
		requestLocations(false)
	}

	def goingFastLane(location : Location) : Boolean = {
		if (fastLaneLoc == null) {
			// need to set fastLaneLoc before re-requesting locations
			fastLaneLoc = location
			startFastLane()
		} else
			fastLaneLoc = location
		return true
	}

	var fixTs = 0L
	var lastLocTs = 0L
	def logPrecision(location : Location) {
		import java.io._
		val path = android.os.Environment.getExternalStorageDirectory()
		val f = new FileWriter(new File(path, "aprsdroid-fix.log"), true)
		if (location.getTime - lastLocTs > 10000) // more than 10s passed
			fixTs = location.getTime
		val deltaT = location.getTime - fixTs
		Log.d(TAG, "logPrecision: %d %d %1.0f".format(location.getTime, deltaT, location.getAccuracy))
		f.write("%d %d %1.0f %s\n".format(location.getTime, deltaT, location.getAccuracy, location.hasSpeed))
		f.close()
		lastLocTs = location.getTime
	}

	// LocationListener interface
	override def onLocationChanged(location : Location) {
		val upd_int = prefs.getStringInt("interval", 10) * 60000
		val upd_dist = prefs.getStringInt("distance", 10) * 1000
		logPrecision(location)
		//Log.d(TAG, "onLocationChanged: n=" + location)
		//Log.d(TAG, "onLocationChanged: l=" + lastLoc)
		if (lastLoc != null &&
		    (location.getTime - lastLoc.getTime < (upd_int  - getGpsInterval()) ||
		     location.distanceTo(lastLoc) < upd_dist)) {
			Log.d(TAG, "onLocationChanged: ignoring premature location")
			return
		}
		// check if we need to go fast lane
		val gps_act = prefs.getString("gps_activation", "med")
		if (gps_act == "med" && location.getProvider == LocationManager.GPS_PROVIDER) {
			if (goingFastLane(location))
				return
		}
		postLocation(location)
	}

	def appVersion() : String = {
		val pi = getPackageManager().getPackageInfo(getPackageName(), 0)
		"APDR%s".format(pi.versionName filter (_.isDigit) take 2)
	}

	def postLocation(location : Location) {
		lastLoc = location

		val i = new Intent(UPDATE)
		i.putExtra(LOCATION, location)

		val callssid = prefs.getCallSsid()
		var symbol = prefs.getString("symbol", "")
		if (symbol.length != 2)
			symbol = getString(R.string.default_symbol)
		val status = prefs.getString("status", getString(R.string.default_status))
		val packet = AprsPacket.formatLoc(callssid, appVersion(), symbol, status, location)

		Log.d(TAG, "packet: " + packet)
		val result = try {
			val status = poster.update(packet)
			i.putExtra(STATUS, status)
			i.putExtra(PACKET, packet)
			val prec_status = "%s (±%dm)".format(status, location.getAccuracy.asInstanceOf[Int])
			addPost(StorageDatabase.Post.TYPE_POST, prec_status, packet)
			prec_status
		} catch {
			case e : Exception =>
				i.putExtra(PACKET, e.getMessage())
				addPost(StorageDatabase.Post.TYPE_ERROR, "Error", e.getMessage())
				e.getMessage()
		}
		if (singleShot) {
			singleShot = false
			stopSelf()
		} else {
			val message = "%s: %s".format(callssid, result)
			ServiceNotifier.instance.start(this, message)
		}
	}

	override def onProviderDisabled(provider : String) {
		Log.d(TAG, "onProviderDisabled: " + provider)
	}
	override def onProviderEnabled(provider : String) {
		Log.d(TAG, "onProviderEnabled: " + provider)
	}
	override def onStatusChanged(provider : String, st: Int, extras : Bundle) {
		Log.d(TAG, "onStatusChanged: " + provider)
	}

	def addPost(t : Int, status : String, message : String) {
		db.addPost(System.currentTimeMillis(), t, status, message)
		sendBroadcast(new Intent(UPDATE).putExtra(STATUS, message))
	}

	def postSubmit(post : String) {
		handler.post { addPost(StorageDatabase.Post.TYPE_INCMG, "incoming", post) }
	}

	def postAbort(post : String) {
		handler.post {
			addPost(StorageDatabase.Post.TYPE_ERROR, "Error", post)
			stopSelf()
		}
	}
}

