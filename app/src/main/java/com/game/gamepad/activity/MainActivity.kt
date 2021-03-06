package com.game.gamepad.activity

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.game.gamepad.R
import com.game.gamepad.utils.BlueToothUtil
import com.game.gamepad.utils.EasyRequest
import com.game.gamepad.utils.SnackbarUtil
import com.game.gamepad.config.ConfigFactory
import com.game.gamepad.widget.GameButton
import com.google.android.material.snackbar.Snackbar
import com.smarx.notchlib.NotchScreenManager
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

//todo
// 1.制作摇杆控件
// 2.美化菜单界面
class MainActivity : Activity(), View.OnClickListener, BlueToothUtil.BluetoothListener,
    ChooseConfigDialog.ChooseConfigDialogListener, SaveConfigDialog.SaveConfigDialogListener {
    private val tag = "MainActivity"

    //记录连续按两次退出
    private var lastTime: Long = 0L

    //管理权限
    private val permissionManager = EasyRequest()

    //用来标记当前所有按钮是否可以修改。
    private var isModifying = false

    //所有button的集合 指当前配置的button
    private val gameButtonList = ArrayList<GameButton>()

    //蓝牙设备
    private val deviceList = ArrayList<BluetoothDevice>()

    //蓝牙名称集合
    private val deviceNameList = ArrayList<String>()

    //蓝牙列表适配器
    private val deviceAdapter by lazy {
        ArrayAdapter<String>(
            this,
            R.layout.simple_spinner_item, deviceNameList
        )
    }

    //线程等待队列
    private val workQueue = LinkedBlockingDeque<Runnable>(10)

    //线程池
    private val threadPool = ThreadPoolExecutor(
        2,
        4,
        5000,
        TimeUnit.MILLISECONDS,
        workQueue
    ) { _, _ -> SnackbarUtil.show("线程池爆了，操作不要太频繁哦!") }

    //震动
    private val vibrator: Vibrator by lazy {
        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    //便于模拟器调试，
    private val debug = true
    private val removeListener = object : GameButton.RemoveListener {
        override fun remove(button: GameButton) {
            //home.removeView(button.getLayou())
            gameButtonList.remove(button)
        }
    }

    private val bluetoothStateChangedReceive = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent!!.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0)) {
                        BluetoothAdapter.STATE_ON -> {
                            Thread {
                                loadDevice()
                            }.run()
                        }
                    }
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (!deviceList.contains(device)) {
                        deviceList.add(device)
                        deviceNameList.add(device.name)
                        GlobalScope.launch(Dispatchers.Main) {
                            deviceAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //f第三方库，适配异形屏
        NotchScreenManager.getInstance().setDisplayInNotch(this)
        //设置全屏
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_main)
        //注册广播
        registerBroadcast()
        //申请权限，
        //requestPermission()
        init()
    }

    override fun onDestroy() {
        //销毁时不需要在返回消息
        BlueToothUtil.disConnect(false)
        unregisterBroadcast()
        super.onDestroy()
    }

    //发出蓝牙扫描的接受广播
    private fun registerBroadcast() {
        val filter = IntentFilter()
        filter.addAction(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED) //动作状态发生了变化
        registerReceiver(bluetoothStateChangedReceive, filter)
    }

    private fun unregisterBroadcast() {
        unregisterReceiver(bluetoothStateChangedReceive)
    }

    /**
     * 这一段本来是为ble准备的，但是一直都没有写ble的代码，所以也就不用了
     * 经典蓝牙不需要定位权限就能获取
     */
    /*
    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (permissionManager.checkPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
                permissionManager.request(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    1
                )
            } else {
                if (permissionManager.checkPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                ) {
                    permissionManager.request(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        2
                    )
                } else {
                    init()
                }
            }
        } else {
            init()
        }
    }
    */
    private fun init() {
        //用于保存配置
        ConfigFactory.init(this)
        checkBlueToothState()
        //初始化蓝牙
        BlueToothUtil.init()
        BlueToothUtil.setListener(this)

        //初始化toast工具
        SnackbarUtil.init(home)
        //先加载一次已绑定设备
        loadDevice()

        deviceSpinner.adapter = deviceAdapter
        deviceAdapter.notifyDataSetChanged()

        chooseConfig.findViewById<TextView>(R.id.buttonText).text = "选择配置"
        saveConfig.findViewById<TextView>(R.id.buttonText).text = "保存配置"
        modifyConfig.findViewById<TextView>(R.id.buttonText).text = "修改配置"
        addConfig.findViewById<TextView>(R.id.buttonText).text = "添加配置"
        deleteConfig.findViewById<TextView>(R.id.buttonText).text = "删除配置"

        //连接和断开连接
        connectDevice.setOnClickListener(this)
        disconnectDevice.setOnClickListener(this)
        //添加按钮
        addButton.setOnClickListener(this)
        //添加配置
        addConfig.setOnClickListener(this)
        //删除配置
        deleteConfig.setOnClickListener(this)
        //选择配置
        chooseConfig.setOnClickListener(this)
        //保存配置
        saveConfig.setOnClickListener(this)
        //返回到主页面
        back.setOnClickListener(this)
        //设置页面
        setting.setOnClickListener(this)
        //修改配置
        modifyConfig.setOnClickListener(this)
        //刷新设备
        reFresh.setOnClickListener(this)
        //自动选择配置
        //chooseConfig.callOnClick()
        openSetting()
        //closeSetting()
    }

    private fun checkBlueToothState() {
        val state = BlueToothUtil.getState()
        if (state == BlueToothUtil.DISABLE) {
            AlertDialog.Builder(this)
                .setTitle("提示")
                .setMessage("此设备不支持蓝牙\n2秒后将退出程序")
                .setCancelable(false)
                .setPositiveButton("退出") { _, _ -> finish() }
                .show()
            Timer().schedule(object : TimerTask() {
                override fun run() {
                    finish()
                }
            }, 2000)
        }
    }

    private fun loadDevice() {
        if (BlueToothUtil.getState() != BlueToothUtil.DISCONNECTED) {
            return
        }
        val devices = BlueToothUtil.getDevices()
        BlueToothUtil.search()
        devices.forEach { d ->
            deviceList.add(d)
            deviceNameList.add(d.name)
        }
        GlobalScope.launch(Dispatchers.Main) {
            deviceAdapter.notifyDataSetChanged()
        }
    }

    private fun isMain(msg: String) {
        if (mainLooper.thread.id == Thread.currentThread().id)
            Log.e(msg, "当前线程是主线程")
        else
            Log.e(msg, "当前线程不是主线程")
    }

    private fun createButton() {
        //threadPool.execute {
        //isMain("createButton")
        val key = keyValue.selectedItem.toString()
        var tmp = buttonType.selectedItem.toString()
        val typeValue = if (tmp == "方块") 1
        else 0
        if (key == "") return
        var xValue = 500f
        var yValue = 500f
        var radiusValue = 100
        var infoValue = "按钮"
        try {
            val xText = x.text.toString()
            val yText = y.text.toString()
            val rText = radius.text.toString()
            val tText = buttonInfo.text.toString()

            if (xText != "")
                xValue = xText.toFloat()
            if (yText != "")
                yValue = yText.toFloat()
            if (rText != "")
                radiusValue = rText.toInt()
            if (tText != "")
                infoValue = tText
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "参数有误", Toast.LENGTH_SHORT).show()
            return
        }
        val gameButton =
            GameButton(
                home.context,
                home,
                removeListener,
                typeValue,
                key,
                infoValue,
                xValue,
                yValue,
                radiusValue
            )
        gameButtonList.add(gameButton)
        //}
    }

    private fun clearButton() {
        for (index in gameButtonList.indices) {
            gameButtonList[index].destroy(false)
        }
        gameButtonList.clear()
    }

    private fun openSetting() {
        threadPool.execute {
            val animatorSet = AnimatorSet()
            animatorSet.playTogether(
                //setting界面淡入
                valueAnimatorCreate(0f, 1f) { animator ->
                    settingLayout.alpha = animator.animatedValue as Float
                },
                //主界面淡出
                valueAnimatorCreate(1f, 0f) { animator ->
                    home.alpha = animator.animatedValue as Float
                },
                valueAnimatorCreate(-deviceLayout.height, 0) { animator ->
                    val layoutParams = deviceLayout.layoutParams as RelativeLayout.LayoutParams
                    layoutParams.topMargin = animator.animatedValue as Int
                    deviceLayout.layoutParams = layoutParams
                },
                valueAnimatorCreate(-configLayout.width, 0) { animator ->
                    val layoutParams = configLayout.layoutParams as RelativeLayout.LayoutParams
                    layoutParams.leftMargin = animator.animatedValue as Int
                    configLayout.layoutParams = layoutParams
                },
                valueAnimatorCreate(-buttonLayout.width, 0) { animator ->
                    val layoutParams = buttonLayout.layoutParams as RelativeLayout.LayoutParams
                    layoutParams.rightMargin = animator.animatedValue as Int
                    buttonLayout.layoutParams = layoutParams
                }
            )
            animatorSet.duration = 400
            animatorSet.addListener(object : Animator.AnimatorListener {
                override fun onAnimationRepeat(animation: Animator?) {}
                override fun onAnimationEnd(animation: Animator?) {
                    //home.visibility = View.GONE
                    animatorSet.removeAllListeners()
                }

                override fun onAnimationCancel(animation: Animator?) {}
                override fun onAnimationStart(animation: Animator?) {
                    settingLayout.visibility = View.VISIBLE
                }
            })
            runOnUiThread {
                animatorSet.start()
            }
        }
    }

    private fun closeSetting() {
        threadPool.execute {
            val animatorSet = AnimatorSet()
            animatorSet.playTogether(
                //两个界面交替
                valueAnimatorCreate(1f, 0f) { animator ->
                    settingLayout.alpha = animator.animatedValue as Float
                },
                valueAnimatorCreate(0f, 1f) { animator ->
                    home.alpha = animator.animatedValue as Float
                },
                valueAnimatorCreate(0, -deviceLayout.height) { animator ->
                    val layoutParams = deviceLayout.layoutParams as RelativeLayout.LayoutParams
                    layoutParams.topMargin = animator.animatedValue as Int
                    deviceLayout.layoutParams = layoutParams
                },
                valueAnimatorCreate(0, -configLayout.width) { animator ->
                    val layoutParams = configLayout.layoutParams as RelativeLayout.LayoutParams
                    layoutParams.leftMargin = animator.animatedValue as Int
                    configLayout.layoutParams = layoutParams
                },
                valueAnimatorCreate(0, -buttonLayout.width) { animator ->
                    val layoutParams = buttonLayout.layoutParams as RelativeLayout.LayoutParams
                    layoutParams.rightMargin = animator.animatedValue as Int
                    buttonLayout.layoutParams = layoutParams
                }
            )
            animatorSet.duration = 400
            animatorSet.addListener(object : Animator.AnimatorListener {
                override fun onAnimationRepeat(animation: Animator?) {}
                override fun onAnimationEnd(animation: Animator?) {
                    settingLayout.visibility = View.GONE
                    animatorSet.removeAllListeners()
                }

                override fun onAnimationCancel(animation: Animator?) {}
                override fun onAnimationStart(animation: Animator?) {
                    //home.visibility = View.VISIBLE
                }
            })
            runOnUiThread {
                animatorSet.start()
            }
        }
    }

    private fun valueAnimatorCreate(
        from: Int,
        to: Int,
        run: (ValueAnimator) -> Unit
    ): ValueAnimator {
        val deviceLayoutAnimator = ValueAnimator.ofInt(from, to)
        deviceLayoutAnimator.addUpdateListener { animation ->
            run(animation)
        }
        return deviceLayoutAnimator
    }

    private fun valueAnimatorCreate(
        from: Float,
        to: Float,
        run: (ValueAnimator) -> Unit
    ): ValueAnimator {
        val deviceLayoutAnimator = ValueAnimator.ofFloat(from, to)
        deviceLayoutAnimator.addUpdateListener { animation ->
            run(animation)
        }
        return deviceLayoutAnimator
    }

    override fun onClick(v: View?) {
        if (v == null) return
        when (v) {
            //添加按钮
            addButton -> {
                //关闭设置界面
                closeSetting()
                createButton()
            }
            //打开设置
            setting -> {
                //settingLayout.visibility = View.VISIBLE
                openSetting()
            }
            //设置界面的返回按钮
            back -> {
                closeSetting()
            }
            //添加新配置
            addConfig -> {
                threadPool.execute {
                    closeSetting()
                    clearButton()
                }
            }
            //删除配置
            deleteConfig -> {
                DeleteConfigDialog(this).show()
            }
            //对按钮进行操作
            modifyConfig -> {
                //关闭设置界面
                closeSetting()
                if (gameButtonList.isNotEmpty()) {
                    isModifying = !isModifying
                    val tmp = isModifying
                    gameButtonList.forEach { btn ->
                        btn.setModifyState(tmp)
                    }
                }
            }
            //选择配置
            chooseConfig -> {
                ChooseConfigDialog(this).setListener(this).show()
            }
            //保存设置
            saveConfig -> {
                SaveConfigDialog(this).setListener(this).show()
            }
            //连接设备
            connectDevice -> {
                val index = deviceSpinner.selectedItemPosition
                if (index == -1) return
                BlueToothUtil.connect(index)
            }
            //断开连接
            disconnectDevice -> {
                BlueToothUtil.disConnect(true)
            }
            //刷新设备
            reFresh -> {
                BlueToothUtil.search()
            }
        }
    }

    override fun onBackPressed() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTime <= 1500) {//1s
            return super.onBackPressed()
        } else {
            lastTime = currentTime
            Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show()
        }
    }

//    override fun onRequestPermissionsResult(
//        requestCode: Int,
//        permissions: Array<out String>,
//        grantResults: IntArray
//    ) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//        when (requestCode) {
//            1, 2 -> {
//                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//                    init()
//                } else {
//                    Toast.makeText(this, "未授权,请手动授权", Toast.LENGTH_SHORT).show()
//                    AlertDialog.Builder(this)
//                        .setTitle("请求授权")
//                        .setMessage("是否手动授权")
//                        .setPositiveButton("是") { _, _ ->
//                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
//                            val uri = Uri.fromParts("package", packageName, null)
//                            intent.data = uri
//                            startActivity(intent)
//                        }
//                        .setNegativeButton("否") { _, _ -> finish() }
//                        .show()
//                }
//            }
//        }
//    }

//    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
//        super.onActivityResult(requestCode, resultCode, data)
//        if (resultCode == RESULT_CANCELED) {
//            if (!permissionManager.checkPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
//                || !permissionManager.checkPermission(
//                    this,
//                    Manifest.permission.ACCESS_COARSE_LOCATION
//                )
//            ) {
//                requestPermission()
//            } else {
//                finish()
//            }
//        }
//    }

    /**
     * @param state 只有当connected = false 才有意义
     * 0 连接失败
     * 1 断开连接
     */
    override fun connected(connected: Boolean, state: Int) {
        runOnUiThread {
            if (connected) {
                //下面这两个一个是设置页面的连接状态一个是主页面的连接状态
                connectState.isActivated = true
                connectionState.isActivated = true
                Snackbar.make(home, "\t连接成功", Snackbar.LENGTH_SHORT).show()
                //Toast.makeText(this, "连接成功", Toast.LENGTH_SHORT).show()
                connectDevice.isEnabled = false
                disconnectDevice.isEnabled = true
                //开启蓝牙活跃
//                threadPool.execute {
                BlueToothUtil.positive()
//                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(300, 1))
                } else {
                    vibrator.vibrate(300)
                }
                connectState.isActivated = false
                connectionState.isActivated = false
                Snackbar.make(home, "\t连接失败", Snackbar.LENGTH_SHORT).show()
                //Toast.makeText(this, "连接失败", Toast.LENGTH_SHORT).show()
                connectDevice.isEnabled = true
                disconnectDevice.isEnabled = false
            }
        }
    }

    override fun onChooseDialogOver(changed: Boolean, name: String) {
        if (changed) {
            threadPool.execute {
                //清除按钮，载入新配置
                closeSetting()
                clearButton()
                val list = ConfigFactory.loadConfig(removeListener, home, name)
                for (gameButton in list) {
                    gameButtonList.add(gameButton)
                }
            }
        }
    }

    override fun onSaveDialogOver(name: String) {
        var json = ""
        for ((index, gameButton) in gameButtonList.withIndex()) {
            json += gameButton.getBean()
            if (index != gameButtonList.size - 1)
                json += ","
        }
        ConfigFactory.save(name, "{\"desc\":\"nothing\",\"buttons\":[$json]}")
    }
}

