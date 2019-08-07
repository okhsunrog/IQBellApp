package com.gornushko.iqbell

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.AsyncTask
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.jetbrains.anko.doAsync
import java.io.IOException
import java.io.InputStream
import java.lang.Exception
import java.util.*
import android.app.NotificationManager
import android.app.NotificationChannel
import java.io.OutputStream
import java.util.zip.CRC32


@ExperimentalUnsignedTypes
class IQService: Service() {

    private var pi: PendingIntent? = null
    private var btAdapter: BluetoothAdapter? = null
    private var connectThread: ConnectThread
    private var check: CheckConnectionThread? = null
    private var socket: BluetoothSocket? = null
    private var auth: Authorization? = null
    private lateinit var currentPassword: ByteArray

    init {
        connectThread = ConnectThread()
    }

    companion object{
        const val address = "C2:2C:05:04:04:FA"
        val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "My Bluetooth Service"
        private const val NOTIFICATIONS_CHANNEL = "new_channel"
        const val BT_OFF = 400
        const val BT_NOT_SUPPORTED = 1
        const val PENDING_INTENT = "pint"
        const val ACTION = "action"
        const val START = 1
        const val CHECK_PAIRED = 2
        const val CONNECTION = 3
        const val RECONNECTING = 5
        const val CONNECTED = 6
        const val NOT_PAIRED = 4
        const val AUTH = 7
        const val PASSWORD = "pass"
        const val AUTH_SUCCEED = 12
        const val AUTH_FAILED = 10
        const val AUTH_PROCESS = 11
        const val STOP_SERVICE = 404
        const val NEW_PENDING_INTENT = 301
        const val GET_INFO = 22
        const val GOT_INFO = 23
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        btAdapter = BluetoothAdapter.getDefaultAdapter()
        val btIntent = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(mBroadcastReceiver, btIntent)
    }

    private val mBroadcastReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when(intent!!.action){
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)){
                        BluetoothAdapter.STATE_ON -> checkPaired()
                        BluetoothAdapter.STATE_TURNING_OFF -> {
                            stop()
                            pi?.send(BT_OFF)
                            Log.d(TAG, "BT is off!")
                            updateNotification(getString(R.string.preparing), true)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        unregisterReceiver(mBroadcastReceiver)
        Log.d(TAG, "Destroyed")

    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind")
        return super.onUnbind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Start command")
        when(intent!!.getIntExtra(ACTION, START)){
            START ->{
                pi = intent.getParcelableExtra(PENDING_INTENT)
                if (btAdapter == null) {
                    pi?.send(BT_NOT_SUPPORTED)
                    stopSelf()
                } else{
                    updateNotification(getString(R.string.preparing), true)
                    checkBt()
                }
            }
            NEW_PENDING_INTENT -> {
                pi = intent.getParcelableExtra(PENDING_INTENT)
            }
            CHECK_PAIRED -> if(btAdapter!!.isEnabled) checkPaired()
            AUTH -> authorize(intent.getByteArrayExtra(PASSWORD)!!)
            STOP_SERVICE -> {
                stop()
                stopForeground(true)
                stopSelf()
            }
            GET_INFO -> {
                Log.d(TAG, "getInfo: starting job")
                getInfo()
            }
        }
        return START_NOT_STICKY
    }

    private fun updateNotification(text: String, progress: Boolean){
        createNotificationsChannel()
        val builder = NotificationCompat.Builder(this, NOTIFICATIONS_CHANNEL)
            .setSmallIcon(R.drawable.ic_bell_outline)
            .setContentTitle(getText(R.string.iq_status))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null, AudioManager.STREAM_RING)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        if(progress) builder.setProgress(0 ,0, true)
        val notification = builder.build()
        startForeground(1, notification)
    }

    private fun createNotificationsChannel(){
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATIONS_CHANNEL, "Connection status",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "IQBell Status"
            channel.enableVibration(false)
            channel.enableLights(false)
            channel.lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(p0: Intent?): IBinder? {
        Log.d(TAG, "onBind")
        return null
    }

    private fun checkBt(){
        if (!btAdapter!!.isEnabled){
            pi?.send(BT_OFF)
            updateNotification(getString(R.string.preparing), true)
        }
        else checkPaired()
    }

    fun checkPaired(){
        var isPaired = false
        for (device: BluetoothDevice in btAdapter!!.bondedDevices) if (device.address == address) isPaired = true
        if (isPaired){
            pi?.send(CONNECTION)
            updateNotification(getString(R.string.connecting), true)
            start()
        }
        else{
            pi?.send(NOT_PAIRED)
            updateNotification(getString(R.string.preparing), true)
        }
    }

    private fun start(){
        check = CheckConnectionThread()
        check!!.start()
    }

    fun stop() {
        doAsync {
            auth?.cancel(true)
            check?.flag = false
            check?.interrupt()
            check?.join()
            check = null
            connectThread.flag = false
            connectThread.interrupt()
            connectThread.join()
            check?.flag = false
            check?.interrupt()
            check?.join()
            check = null
            try {
                socket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the client socket: " + e.message)
            }
            Log.d(TAG, "FINISHED")
        }
    }

    private inner class ConnectThread: Thread() {

        var flag = true

        override fun run() {
            // Cancel discovery because it otherwise slows down the connection.
            btAdapter?.cancelDiscovery()
            while (flag) {
                Log.d(TAG, "Attempt to connect...")
                var mmSocket: BluetoothSocket?
                try{
                    mmSocket = btAdapter?.getRemoteDevice(address)?.createRfcommSocketToServiceRecord(MY_UUID) ?: break
                } catch (e: Exception){ break }
                try {
                    // Connect to the remote device through the socket. This call blocks
                    // until it succeeds or throws an exception.
                    mmSocket.connect()
                    Log.d(TAG, "Connected?")
                } catch (e: IOException) {
                    Log.d(TAG, "An error occurred during connection process: " + e.message)
                    break
                }
                if (mmSocket.isConnected) {
                    socket = mmSocket
                    pi?.send(CONNECTED)
                    updateNotification(getString(R.string.connected), false)
                    break
                } else {
                    pi?.send(RECONNECTING)
                    Log.d(TAG, "reconnecting")
                    updateNotification(getString(R.string.connecting), true)
                    try {
                        mmSocket.close()
                        Log.d(TAG, "Socket closed")
                    } catch (e: IOException) {
                        Log.e(TAG, "Could not close the client socket: " + e.message)
                    }
                }
                try{
                    sleep(1_000)
                } catch (e: InterruptedException){}
            }
        }
    }

    private inner class CheckConnectionThread: Thread(){
        var counter = 0
        var flag = true
        override fun run() {
            while (flag) {
                if (!connectThread.isAlive) {
                    if (socket?.isConnected != true) {
                        Log.d(TAG, "socket not equals true!")
                        connectThread = ConnectThread()
                        connectThread.start()
                    }
                    if (socket?.isConnected == true) {
                        try {
                            val oStream = socket!!.outputStream
                            val iStream = socket!!.inputStream
                            oStream.write(1)
                            oStream.flush()
                            sleep(100)
                            if (iStream.available() > 0) {
                                if (iStream.read() == 5) {
                                    counter = 0
                                } else counter++
                                iStream.skip(iStream.available().toLong())
                            } else counter++
                            if (counter == 3) {
                                counter = 0
                                pi?.send(RECONNECTING)
                                updateNotification(getString(R.string.connecting), true)
                                Log.d(TAG, "counter!!!")
                                connectThread = ConnectThread()
                                connectThread.start()
                            }
                        } catch (e: Exception) {
                            pi?.send(RECONNECTING)
                            updateNotification(getString(R.string.connecting), true)
                            connectThread = ConnectThread()
                            connectThread.start()
                        }
                    }
                }
                try {
                    sleep(400)
                } catch (e: InterruptedException) {
                    Log.d(TAG, "Interrupted")
                }
            }
        }
    }

    private fun authorize(pass: ByteArray){
        auth = Authorization()
        currentPassword = pass
        auth?.execute(pass)
    }

    @SuppressLint("StaticFieldLeak")
    private inner class Authorization : AsyncTask<ByteArray, Void, Boolean>() {

        override fun doInBackground(vararg p0: ByteArray?): Boolean {
            check?.flag = false
            Log.d(TAG, "Interrupt succeed")
            check?.join()
            Log.d(TAG, "Joined")
            try{
                val oStream = socket!!.outputStream
                oStream.write(p0[0]!!)
                oStream.flush()
            }catch (e: Exception){}
            try {
                Thread.sleep(1_000)
            }  catch (e: InterruptedException){
            }
            var code = 0
            try{
                val iStream = socket!!.inputStream
                if(iStream.available() > 0){
                    code = iStream.read()
                    clearInput(iStream)
                }
            }catch(e: Exception){}
            check = null
            check = CheckConnectionThread()
            check!!.start()
            Log.d(TAG, "Code is $code")
            return (code == 11)
        }

        override fun onPreExecute() {
            super.onPreExecute()
            pi?.send(AUTH_PROCESS)
        }

        override fun onPostExecute(result: Boolean?) {
            super.onPostExecute(result)
            pi?.send(if(result!!) AUTH_SUCCEED else AUTH_FAILED)
            if(result!!) updateNotification(getString(R.string.authorized), false)

        }
    }

    private fun getInfo(){
        doAsync {
            Log.d(TAG, "Started async task getInfo()")
            check?.flag = false
            Log.d(TAG, "Interrupt succeed")
            check?.join()
            Log.d(TAG, "Joined")
            try{
                val iStream = socket!!.inputStream
                val oStream = socket!!.outputStream
                oStream?.write(currentPassword)  //authorization
                oStream?.flush()
                Thread.sleep(100) //waiting for answer
                var code = 0
                if(iStream.available() > 0) {
                    code = iStream.read() //reading the answer
                    clearInput(iStream)
                }
                Log.d(TAG, "code is: $code")
                if(code == 11){ //if authorization succeed
                    val myData = byteArrayOf(0x0, 0x6e, 0x75, 0x6c, 0x6c)
                    oStream.write(myData)
                    val crc = CRC32()
                    crc.reset()
                    crc.update(myData)
                    sendLong(crc.value, oStream) //sending checksum
                    Thread.sleep(1_000)
                    Log.d(TAG, "Data available: ${iStream.available()}")
                    if(iStream.available() > 0){
                        val nByte = iStream.read()
                        Log.d(TAG, "nByte: $nByte")
                        if(nByte == 11){
                            crc.reset()
                            crc.update(nByte)
                            val deviceTime = (getLong(iStream, crc)-10800)*1000 //-3 h (Arduino stores MSC time, Android - UTC)
                            if(getLong(iStream, null) == crc.value){
                                val deviceDate = Date(deviceTime)
                                Log.d(TAG, "Time is: $deviceDate")
                                pi?.send(applicationContext, GOT_INFO, Intent().putExtra("date", deviceTime))
                            } else Log.d(TAG, "The data was corrupted during sending to Android")
                        } else Log.d(TAG, "The data was corrupted during sending to Arduino")
                    }
                }
            } catch (e: Exception){}
            check = null
            check = CheckConnectionThread()
            check!!.start()
        }
    }

    private fun clearInput(str: InputStream){
        while (str.available() > 0) str.read()
    }

    private fun sendLong(sum: Long, stream: OutputStream){
        val tempSum = sum.toUInt()
        for(i in 0..3) stream.write((tempSum shr 8*i).toUByte().toInt())
    }

    private fun getLong(stream: InputStream, crc32: CRC32?): Long{
        val tempData = ByteArray(4)
        stream.read(tempData)
        crc32?.update(tempData)
        var result = 0u
        for(i in 3 downTo 0) result = (result shl 8) + tempData[i].toUByte()
        return result.toLong()
    }
}