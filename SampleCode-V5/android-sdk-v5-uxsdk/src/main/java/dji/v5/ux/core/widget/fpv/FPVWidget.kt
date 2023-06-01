/*
 * Copyright (c) 2018-2020 DJI
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */
package dji.v5.ux.core.widget.fpv

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.Dimension
import androidx.annotation.FloatRange
import androidx.annotation.StyleRes
import androidx.constraintlayout.widget.Guideline
import androidx.core.content.res.use
import dji.sdk.keyvalue.value.mop.PipelineDeviceType
import dji.sdk.keyvalue.value.mop.TransmissionControlType
import dji.v5.common.error.DJIPipeLineError
import dji.v5.common.video.channel.VideoChannelType
import dji.v5.common.video.decoder.DecoderOutputMode
import dji.v5.common.video.decoder.DecoderState
import dji.v5.common.video.decoder.VideoDecoder
import dji.v5.common.video.interfaces.IVideoDecoder
import dji.v5.common.video.stream.PhysicalDevicePosition
import dji.v5.common.video.stream.StreamSource
import dji.v5.manager.mop.DataResult
import dji.v5.manager.mop.Pipeline
import dji.v5.manager.mop.PipelineManager
import dji.v5.utils.common.DJIExecutor
import dji.v5.utils.common.DisplayUtil
import dji.v5.utils.common.JsonUtil
import dji.v5.utils.common.LogUtils
import dji.v5.utils.common.ToastUtils
import dji.v5.ux.R
import dji.v5.ux.core.base.DJISDKModel
import dji.v5.ux.core.base.SchedulerProvider
import dji.v5.ux.core.base.widget.ConstraintLayoutWidget
import dji.v5.ux.core.communication.ObservableInMemoryKeyedStore
import dji.v5.ux.core.extension.*
import dji.v5.ux.core.module.FlatCameraModule
import dji.v5.ux.core.ui.CenterPointView
import dji.v5.ux.core.ui.GridLineView
import dji.v5.ux.core.util.RxUtil
import dji.v5.ux.core.widget.fpv.FPVWidget.ModelState
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit

private const val TAG = "FPVWidget"
private const val ORIGINAL_SCALE = 1f
private const val LANDSCAPE_ROTATION_ANGLE = 0
// private val LABELS = listOf("病虫害")
private val LABELS = listOf("人", "自行车", "汽车", "摩托车", "飞机", "公共汽车", "火车", "卡车", "船", "交通灯", "消防栓", "停车标志", "停车计时器", "长凳", "鸟", "猫", "狗", "马", "羊", "牛", "大象", "熊", "斑马", "长颈鹿", "背包", "雨伞", "手提包", "领带", "手提箱", "飞盘", "滑雪板", "滑雪板", "运动球", "风筝", "棒球棒", "棒球手套", "滑板", "冲浪板", "网球拍", "瓶子", "酒杯", "杯子", "叉子", "刀子", "勺子", "碗", "香蕉", "苹果", "三明治", "橘子", "西兰花", "胡萝卜", "热狗", "披萨", "甜甜圈", "蛋糕", "椅子", "沙发", "盆栽", "床", "餐桌", "厕所", "电视机", "笔记本电脑", "鼠标", "遥控器", "键盘", "手机", "微波炉", "烤箱", "烤面包机", "水槽", "冰箱", "书本", "时钟", "花瓶", "剪刀", "泰迪熊", "吹风机", "牙刷")
// private val LABELS = listOf("行人", "人", "自行车", "小汽车", "货车", "卡车", "三轮车", "遮阳篷三轮车", "公共汽车", "摩托车")

/**
 * This widget shows the video feed from the camera.
 */
open class FPVWidget @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayoutWidget<ModelState>(context, attrs, defStyleAttr),
    SurfaceHolder.Callback {
    private var viewWidth = 0
    private var viewHeight = 0
    private var rotationAngle = 0
    private val fpvSurfaceView: SurfaceView = findViewById(R.id.surface_view_fpv)
    private val cameraNameTextView: TextView = findViewById(R.id.textview_camera_name)
    private val cameraSideTextView: TextView = findViewById(R.id.textview_camera_side)
    private val detImageView: DetImageView = findViewById(R.id.image_view_det);
    private val verticalOffset: Guideline = findViewById(R.id.vertical_offset)
    private val horizontalOffset: Guideline = findViewById(R.id.horizontal_offset)
    private var fpvStateChangeResourceId: Int = INVALID_RESOURCE
    private var videoDecoder: IVideoDecoder? = null
    private var mReadFromPipelineDisposable: Disposable? = null
    private var pipelineConnected: Boolean = false
    private var pipelineID: Int = 49155
    private val pipelineDeviceType: PipelineDeviceType = PipelineDeviceType.ONBOARD
    private val transmissionControlType: TransmissionControlType = TransmissionControlType.UNRELIABLE
    private var pipeline: Pipeline? = null
    private val data = ByteArray(1024)

    private val widgetModel: FPVWidgetModel = FPVWidgetModel(
        DJISDKModel.getInstance(), ObservableInMemoryKeyedStore.getInstance(), FlatCameraModule()
    )

    var videoChannelType: VideoChannelType = VideoChannelType.PRIMARY_STREAM_CHANNEL
        set(value) {
            field = value
            widgetModel.videoChannelType = value
        }

    /**
     * Whether the video feed source's camera name is visible on the video feed.
     */
    var isCameraSourceNameVisible = true
        set(value) {
            field = value
            checkAndUpdateCameraName()
        }

    /**
     * Whether the video feed source's camera side is visible on the video feed.
     * Only shown on aircraft that support multiple gimbals.
     */
    var isCameraSourceSideVisible = true
        set(value) {
            field = value
            checkAndUpdateCameraSide()
        }

    /**
     * Whether the grid lines are enabled.
     */
    var isGridLinesEnabled = true
        set(isGridLinesEnabled) {
            field = isGridLinesEnabled
            updateGridLineVisibility()
        }

    /**
     * Whether the center point is enabled.
     */
    var isCenterPointEnabled = true
        set(isCenterPointEnabled) {
            field = isCenterPointEnabled
            centerPointView.visibility = if (isCenterPointEnabled) View.VISIBLE else View.GONE
        }

    var isDetectionEnabled = false
        set(value) {
            field = value
        }

    var isDrawEnabled = false
        set(value) {
            field = value
        }

    /**
     * The text color state list of the camera name text view
     */
    var cameraNameTextColors: ColorStateList?
        get() = cameraNameTextView.textColors
        set(colorStateList) {
            cameraNameTextView.setTextColor(colorStateList)
        }

    /**
     * The text color of the camera name text view
     */
    @get:ColorInt
    @setparam:ColorInt
    var cameraNameTextColor: Int
        get() = cameraNameTextView.currentTextColor
        set(color) {
            cameraNameTextView.setTextColor(color)
        }

    /**
     * The text size of the camera name text view
     */
    @get:Dimension
    @setparam:Dimension
    var cameraNameTextSize: Float
        get() = cameraNameTextView.textSize
        set(textSize) {
            cameraNameTextView.textSize = textSize
        }

    /**
     * The background for the camera name text view
     */
    var cameraNameTextBackground: Drawable?
        get() = cameraNameTextView.background
        set(drawable) {
            cameraNameTextView.background = drawable
        }

    /**
     * The text color state list of the camera name text view
     */
    var cameraSideTextColors: ColorStateList?
        get() = cameraSideTextView.textColors
        set(colorStateList) {
            cameraSideTextView.setTextColor(colorStateList)
        }

    /**
     * The text color of the camera side text view
     */
    @get:ColorInt
    @setparam:ColorInt
    var cameraSideTextColor: Int
        get() = cameraSideTextView.currentTextColor
        set(color) {
            cameraSideTextView.setTextColor(color)
        }

    /**
     * The text size of the camera side text view
     */
    @get:Dimension
    @setparam:Dimension
    var cameraSideTextSize: Float
        get() = cameraSideTextView.textSize
        set(textSize) {
            cameraSideTextView.textSize = textSize
        }

    /**
     * The background for the camera side text view
     */
    var cameraSideTextBackground: Drawable?
        get() = cameraSideTextView.background
        set(drawable) {
            cameraSideTextView.background = drawable
        }

    /**
     * The vertical alignment of the camera name and side text views
     */
    var cameraDetailsVerticalAlignment: Float
        @FloatRange(from = 0.0, to = 1.0)
        get() {
            val layoutParams: LayoutParams = verticalOffset.layoutParams as LayoutParams
            return layoutParams.guidePercent
        }
        set(@FloatRange(from = 0.0, to = 1.0) percent) {
            val layoutParams: LayoutParams = verticalOffset.layoutParams as LayoutParams
            layoutParams.guidePercent = percent
            verticalOffset.layoutParams = layoutParams
        }

    /**
     * The horizontal alignment of the camera name and side text views
     */
    var cameraDetailsHorizontalAlignment: Float
        @FloatRange(from = 0.0, to = 1.0)
        get() {
            val layoutParams: LayoutParams = horizontalOffset.layoutParams as LayoutParams
            return layoutParams.guidePercent
        }
        set(@FloatRange(from = 0.0, to = 1.0) percent) {
            val layoutParams: LayoutParams = horizontalOffset.layoutParams as LayoutParams
            layoutParams.guidePercent = percent
            horizontalOffset.layoutParams = layoutParams
        }

    /**
     * The [GridLineView] shown in this widget
     */
    val gridLineView: GridLineView = findViewById(R.id.view_grid_line)

    /**
     * The [CenterPointView] shown in this widget
     */
    val centerPointView: CenterPointView = findViewById(R.id.view_center_point)

    //endregion

    //region Constructor
    override fun initView(context: Context, attrs: AttributeSet?, defStyleAttr: Int) {
        inflate(context, R.layout.uxsdk_widget_fpv, this)
    }

    init {
        if (!isInEditMode) {
            fpvSurfaceView.holder.addCallback(this)
            rotationAngle = LANDSCAPE_ROTATION_ANGLE
        }
        attrs?.let { initAttributes(context, it) }
    }
    //endregion

    //region LifeCycle
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isInEditMode) {
            widgetModel.setup()
        }
        initializeListeners()
        if (isDetectionEnabled && !pipelineConnected) {
            connectPipeline()
        }
    }

    private fun initializeListeners() {
        //后面补上
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        fpvSurfaceView.visibility = visibility
    }

    override fun onDetachedFromWindow() {
        if (isDetectionEnabled && pipelineConnected) {
            disconnectPipeline()
        }
        destroyListeners()
        if (!isInEditMode) {
            widgetModel.cleanup()
        }
        videoDecoder?.destroy()
        videoDecoder = null
        super.onDetachedFromWindow()
    }

    override fun reactToModelChanges() {
        addReaction(widgetModel.cameraNameProcessor.toFlowable()
            .observeOn(SchedulerProvider.ui())
            .subscribe { cameraName: String -> updateCameraName(cameraName) })
        addReaction(widgetModel.cameraSideProcessor.toFlowable()
            .observeOn(SchedulerProvider.ui())
            .subscribe { cameraSide: String -> updateCameraSide(cameraSide) })
        addReaction(widgetModel.hasVideoViewChanged
            .observeOn(SchedulerProvider.ui())
            .subscribe { delayCalculator() })
    }

    override fun surfaceCreated(holder: SurfaceHolder?) {
        LogUtils.i(logTag, "surfaceCreated", videoChannelType, videoDecoder == null)
        if (videoDecoder == null) {
            videoDecoder = VideoDecoder(
                context,
                videoChannelType,
                DecoderOutputMode.SURFACE_MODE,
                fpvSurfaceView.holder,
                fpvSurfaceView.width,
                fpvSurfaceView.height,
                true
            )
        } else if (videoDecoder?.decoderStatus == DecoderState.PAUSED) {
            videoDecoder?.onResume()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
        if (videoDecoder == null) {
            videoDecoder = VideoDecoder(
                context,
                videoChannelType,
                DecoderOutputMode.SURFACE_MODE,
                fpvSurfaceView.holder,
                fpvSurfaceView.width,
                fpvSurfaceView.height,
                true
            )
        } else if (videoDecoder?.decoderStatus == DecoderState.PAUSED) {
            videoDecoder?.onResume()
        }
        LogUtils.i(logTag, "surfaceChanged", videoChannelType, videoDecoder?.videoWidth, videoDecoder?.videoHeight)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        videoDecoder?.onPause()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!isInEditMode) {
            setViewDimensions()
            delayCalculator()
        }
    }

    private fun destroyListeners() {
        //后面补上
    }

    //endregion
    //region Customization
    override fun getIdealDimensionRatioString(): String {
        return getString(R.string.uxsdk_widget_fpv_ratio)
    }

    fun updateVideoSource(source: StreamSource, channelType: VideoChannelType) {
        LogUtils.i(logTag, "updateVideoSource", JsonUtil.toJson(source), channelType)
        widgetModel.streamSource = source
        if (videoChannelType != channelType) {
            changeVideoDecoder(channelType)
        }
        videoChannelType = channelType
        if (isDetectionEnabled && pipelineConnected) {
            isDrawEnabled = !isDrawEnabled
            if (isDrawEnabled) {
                startReadDataTimer()
            } else {
                stopReadDataTimer()
            }
        }
    }

    fun getStreamSource() = widgetModel.streamSource

    private fun changeVideoDecoder(channel: VideoChannelType) {
        LogUtils.i(logTag, "changeVideoDecoder", channel)
        videoDecoder?.videoChannelType = channel
        fpvSurfaceView.invalidate()
    }

    fun setOnFPVStreamSourceListener(listener: FPVStreamSourceListener) {
        widgetModel.streamSourceListener = listener
    }

    fun setSurfaceViewZOrderOnTop(onTop:Boolean){
        fpvSurfaceView.setZOrderOnTop(onTop)
    }

    fun setSurfaceViewZOrderMediaOverlay(isMediaOverlay:Boolean){
        fpvSurfaceView.setZOrderMediaOverlay(isMediaOverlay)
    }

    fun connectPipeline() {
        val error = PipelineManager.getInstance().connectPipeline(pipelineID, pipelineDeviceType, transmissionControlType)
        if (error == null) {
            pipeline = PipelineManager.getInstance().pipelines[pipelineID]
            pipelineConnected = true
            ToastUtils.showToast("connect to $pipelineID success")
            startReadDataTimer()
        } else {
            ToastUtils.showToast("connect to $pipelineID fail: $error")
        }
    }

    private fun startReadDataTimer() {
        mReadFromPipelineDisposable = Flowable.interval(10,
            TimeUnit.MILLISECONDS,
            Schedulers.from(DJIExecutor.getExecutor()))
            .doOnNext { readFromPipeline() }
            .doOnCancel { LogUtils.e(logTag, "OnCancel") }
            .doOnTerminate { LogUtils.e(logTag, "OnTerminate") }
            .doOnError { throwable: Throwable -> LogUtils.e(logTag, "OnError:" + throwable.localizedMessage) }
            .doOnComplete { LogUtils.e(logTag, "OnComplete") }
            .subscribe()

    }

    private fun disconnectPipeline() {
        stopReadDataTimer()
        pipeline?.let {
            val error = PipelineManager.getInstance().disconnectPipeline(pipelineID, pipelineDeviceType, transmissionControlType)
            if (error == null) {
                pipelineConnected = false;
                ToastUtils.showToast("disconnect from $pipelineID success")
            } else {
                ToastUtils.showToast("disconnect from $pipelineID fail: $error")
            }
        }
    }

    private fun stopReadDataTimer() {
        mReadFromPipelineDisposable?.let {
            mReadFromPipelineDisposable?.dispose()
            mReadFromPipelineDisposable == null
        }
    }

    fun readFromPipeline() {
        val result = pipeline?.readData(data) ?: DataResult()
        val len = result.length
        if (len < 0) {
            if (result.error.errorCode().equals(DJIPipeLineError.TIMEOUT)) {
                ToastUtils.showToast("read timeout")
            } else {
                ToastUtils.showToast("read error: $result")
                disconnectPipeline()
            }
            return
        }

        detImageView.clearBoxes()

        LogUtils.i("read $len bytes")
        assert(len > 0)
        val content = String(data, 0, len);
        LogUtils.i("content: $content")

        val contentsByColon = content.split(":")
        assert(contentsByColon.isNotEmpty())

        val imageSize = contentsByColon[0].split(",")
        assert(imageSize.size == 2)
        val imageWidth = imageSize[0].toInt()
        val imageHeight = imageSize[1].toInt()

        if (contentsByColon.size == 2) {
            val boxes = contentsByColon[1].split(";")
            for (box in boxes) {
                val contentsByComma = box.split(",")
                val x = contentsByComma[0].toInt() * detImageView.width / imageWidth
                val y = contentsByComma[1].toInt() * detImageView.height / imageHeight
                val w = contentsByComma[2].toInt() * detImageView.width / imageWidth
                val h = contentsByComma[3].toInt() * detImageView.height / imageHeight
                val prob = (contentsByComma[4].toDouble() * 100).toInt()
                val classes = contentsByComma[5].toInt()
                val label = LABELS[classes]
                detImageView.addBox(x - w / 2, y - h / 2, w, h, prob, label)
            }
        }

        detImageView.post(Runnable {
            detImageView.invalidate()
            detImageView.draw()
        })
    }

    //endregion
    //region Helpers
    private fun setViewDimensions() {
        viewWidth = measuredWidth
        viewHeight = measuredHeight
    }

    /**
     * This method should not to be called until the size of `TextureView` is fixed.
     */
    public fun changeView(width: Int, height: Int, relativeWidth: Int, relativeHeight: Int) {
        val lp = fpvSurfaceView.layoutParams
        lp.width = width
        lp.height = height
        fpvSurfaceView.layoutParams = lp
        if (width > viewWidth) {
            fpvSurfaceView.scaleX = width.toFloat() / viewWidth
        } else {
            fpvSurfaceView.scaleX = ORIGINAL_SCALE
        }
        if (height > viewHeight) {
            fpvSurfaceView.scaleY = height.toFloat() / viewHeight
        } else {
            fpvSurfaceView.scaleY = ORIGINAL_SCALE
        }
        gridLineView.adjustDimensions(relativeWidth, relativeHeight)
    }

    private fun delayCalculator() {
        //后面补充
    }

    private fun updateCameraName(cameraName: String) {
        cameraNameTextView.text = cameraName
        if (cameraName.isNotEmpty() && isCameraSourceNameVisible) {
            cameraNameTextView.visibility = View.VISIBLE
        } else {
            cameraNameTextView.visibility = View.INVISIBLE
        }
    }

    private fun updateCameraSide(cameraSide: String) {
        cameraSideTextView.text = cameraSide
        if (cameraSide.isNotEmpty() && isCameraSourceSideVisible) {
            cameraSideTextView.visibility = View.VISIBLE
        } else {
            cameraSideTextView.visibility = View.INVISIBLE
        }
    }

    private fun checkAndUpdateCameraName() {
        if (!isInEditMode) {
            addDisposable(
                widgetModel.cameraNameProcessor.toFlowable()
                    .firstOrError()
                    .observeOn(SchedulerProvider.ui())
                    .subscribe(
                        Consumer { cameraName: String -> updateCameraName(cameraName) },
                        RxUtil.logErrorConsumer(TAG, "updateCameraName")
                    )
            )
        }
    }

    private fun checkAndUpdateCameraSide() {
        if (!isInEditMode) {
            addDisposable(
                widgetModel.cameraSideProcessor.toFlowable()
                    .firstOrError()
                    .observeOn(SchedulerProvider.ui())
                    .subscribe(
                        Consumer { cameraSide: String -> updateCameraSide(cameraSide) },
                        RxUtil.logErrorConsumer(TAG, "updateCameraSide")
                    )
            )
        }
    }

    private fun updateGridLineVisibility() {
        gridLineView.visibility = if (isGridLinesEnabled
            && widgetModel.streamSource?.physicalDevicePosition == PhysicalDevicePosition.NOSE) View.VISIBLE else View.GONE
    }
    //endregion

    //region Customization helpers
    /**
     * Set text appearance of the camera name text view
     *
     * @param textAppearance Style resource for text appearance
     */
    fun setCameraNameTextAppearance(@StyleRes textAppearance: Int) {
        cameraNameTextView.setTextAppearance(context, textAppearance)
    }

    /**
     * Set text appearance of the camera side text view
     *
     * @param textAppearance Style resource for text appearance
     */
    fun setCameraSideTextAppearance(@StyleRes textAppearance: Int) {
        cameraSideTextView.setTextAppearance(context, textAppearance)
    }

    @SuppressLint("Recycle")
    private fun initAttributes(context: Context, attrs: AttributeSet) {
        context.obtainStyledAttributes(attrs, R.styleable.FPVWidget).use { typedArray ->
            if (!isInEditMode) {
                typedArray.getIntegerAndUse(R.styleable.FPVWidget_uxsdk_videoChannelType) {
                    videoChannelType = (VideoChannelType.find(it))
                    widgetModel.initStreamSource()
                }
                typedArray.getBooleanAndUse(R.styleable.FPVWidget_uxsdk_gridLinesEnabled, true) {
                    isGridLinesEnabled = it
                }
                typedArray.getBooleanAndUse(R.styleable.FPVWidget_uxsdk_centerPointEnabled, true) {
                    isCenterPointEnabled = it
                }
            }
            typedArray.getBooleanAndUse(R.styleable.FPVWidget_uxsdk_sourceCameraNameVisibility, true) {
                isCameraSourceNameVisible = it
            }
            typedArray.getBooleanAndUse(R.styleable.FPVWidget_uxsdk_sourceCameraSideVisibility, true) {
                isCameraSourceSideVisible = it
            }
            typedArray.getResourceIdAndUse(R.styleable.FPVWidget_uxsdk_cameraNameTextAppearance) {
                setCameraNameTextAppearance(it)
            }
            typedArray.getDimensionAndUse(R.styleable.FPVWidget_uxsdk_cameraNameTextSize) {
                cameraNameTextSize = DisplayUtil.pxToSp(context, it)
            }
            typedArray.getColorAndUse(R.styleable.FPVWidget_uxsdk_cameraNameTextColor) {
                cameraNameTextColor = it
            }
            typedArray.getDrawableAndUse(R.styleable.FPVWidget_uxsdk_cameraNameBackgroundDrawable) {
                cameraNameTextBackground = it
            }
            typedArray.getResourceIdAndUse(R.styleable.FPVWidget_uxsdk_cameraSideTextAppearance) {
                setCameraSideTextAppearance(it)
            }
            typedArray.getDimensionAndUse(R.styleable.FPVWidget_uxsdk_cameraSideTextSize) {
                cameraSideTextSize = DisplayUtil.pxToSp(context, it)
            }
            typedArray.getColorAndUse(R.styleable.FPVWidget_uxsdk_cameraSideTextColor) {
                cameraSideTextColor = it
            }
            typedArray.getDrawableAndUse(R.styleable.FPVWidget_uxsdk_cameraSideBackgroundDrawable) {
                cameraSideTextBackground = it
            }
            typedArray.getFloatAndUse(R.styleable.FPVWidget_uxsdk_cameraDetailsVerticalAlignment) {
                cameraDetailsVerticalAlignment = it
            }
            typedArray.getFloatAndUse(R.styleable.FPVWidget_uxsdk_cameraDetailsHorizontalAlignment) {
                cameraDetailsHorizontalAlignment = it
            }
            typedArray.getIntegerAndUse(R.styleable.FPVWidget_uxsdk_gridLineType) {
                gridLineView.type = GridLineView.GridLineType.find(it)
            }
            typedArray.getColorAndUse(R.styleable.FPVWidget_uxsdk_gridLineColor) {
                gridLineView.lineColor = it
            }
            typedArray.getFloatAndUse(R.styleable.FPVWidget_uxsdk_gridLineWidth) {
                gridLineView.lineWidth = it
            }
            typedArray.getIntegerAndUse(R.styleable.FPVWidget_uxsdk_gridLineNumber) {
                gridLineView.numberOfLines = it
            }
            typedArray.getIntegerAndUse(R.styleable.FPVWidget_uxsdk_centerPointType) {
                centerPointView.type = CenterPointView.CenterPointType.find(it)
            }
            typedArray.getColorAndUse(R.styleable.FPVWidget_uxsdk_centerPointColor) {
                centerPointView.color = it
            }
            typedArray.getResourceIdAndUse(R.styleable.FPVWidget_uxsdk_onStateChange) {
                fpvStateChangeResourceId = it
            }
            typedArray.getBooleanAndUse(R.styleable.FPVWidget_uxsdk_detectionEnabled, false) {
                isDetectionEnabled = it
            }
        }
    }
    //endregion

    /**
     * The size of the video feed within this widget
     *
     * @property width The width of the video feed within this widget
     * @property height The height of the video feed within this widget
     */
    data class FPVSize(val width: Int, val height: Int)

    /**
     * Get the [ModelState] updates
     */
    @SuppressWarnings
    override fun getWidgetStateUpdate(): Flowable<ModelState> {
        return super.getWidgetStateUpdate()
    }

    /**
     * Class defines the widget state updates
     */
    sealed class ModelState
}