package site.leos.apps.lespas.helper

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.*
import androidx.recyclerview.widget.DiffUtil.ItemCallback
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ui.PlayerView
import kotlinx.parcelize.Parcelize
import site.leos.apps.lespas.R
import java.time.LocalDateTime

abstract class MediaSliderAdapter(differ: ItemCallback<Any>, private val clickListener: () -> Unit, private val imageLoader: (Any, ImageView, type: String) -> Unit, private val cancelLoader: (View) -> Unit
): ListAdapter<Any, RecyclerView.ViewHolder>(differ) {
    private lateinit var exoPlayer: SimpleExoPlayer
    private var currentVolume = 0f
    private var oldVideoViewHolder: VideoViewHolder? = null
    private var savedPlayerState = PlayerState()

    abstract fun getVideoItem(position: Int): VideoItem
    abstract fun getItemTransitionName(position: Int): String
    abstract fun getItemMimeType(position: Int): String

    override fun getItemViewType(position: Int): Int {
        with(getItemMimeType(position)) {
            return when {
                this == "image/agif" || this == "image/awebp" -> TYPE_ANIMATED
                this.startsWith("video/") -> TYPE_VIDEO
                else -> TYPE_PHOTO
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when(viewType) {
            TYPE_PHOTO -> PhotoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_photo, parent, false))
            TYPE_ANIMATED -> AnimatedViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_gif, parent, false))
            else-> VideoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_exoplayer, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when(holder) {
            is VideoViewHolder -> holder.bind(getItem(position), getVideoItem(position))
            is AnimatedViewHolder -> holder.bind(getItem(position), getItemTransitionName(position))
            else-> (holder as PhotoViewHolder).bind(getItem(position), getItemTransitionName(position))
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is VideoViewHolder) holder.resume()
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (holder is VideoViewHolder) holder.pause()
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        cancelLoader(holder.itemView.findViewById(R.id.media) as View)
        super.onViewRecycled(holder)
    }

    inner class PhotoViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
        fun bind(photo: Any, transitionName: String) {
            itemView.findViewById<PhotoView>(R.id.media).apply {
                imageLoader(photo, this, ImageLoaderViewModel.TYPE_FULL)
                setOnPhotoTapListener { _, _, _ -> clickListener() }
                setOnOutsidePhotoTapListener { clickListener() }
                maximumScale = 5.0f
                mediumScale = 2.5f
                ViewCompat.setTransitionName(this, transitionName)
            }
        }
    }

    inner class AnimatedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(photo: Any, transitionName: String) {
            itemView.findViewById<ImageView>(R.id.media).apply {
                imageLoader(photo, this, ImageLoaderViewModel.TYPE_FULL)
                setOnClickListener { clickListener() }
                ViewCompat.setTransitionName(this, transitionName)
            }
        }
    }

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private lateinit var videoView: PlayerView
        private lateinit var thumbnailView: ImageView
        private lateinit var muteButton: ImageButton
        private lateinit var videoUri: Uri
        private var videoMimeType = ""
        private var stopPosition = 0L

        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: Any, video: VideoItem) {
            this.videoUri = video.uri

            if (savedPlayerState.stopPosition != FAKE_POSITION) {
                stopPosition = savedPlayerState.stopPosition
                savedPlayerState.stopPosition = FAKE_POSITION

                if (savedPlayerState.isMuted) exoPlayer.volume = 0f
            }
            muteButton = itemView.findViewById(R.id.exo_mute)
            videoView = itemView.findViewById<PlayerView>(R.id.player_view).apply {
                controllerShowTimeoutMs = 3000
                setOnClickListener { clickListener() }
            }

            videoMimeType = video.mimeType

            itemView.findViewById<ConstraintLayout>(R.id.videoview_container).let {
                // Fix view aspect ratio
                if (video.height != 0) with(ConstraintSet()) {
                    clone(it)
                    setDimensionRatio(R.id.media, "${video.width}:${video.height}")
                    applyTo(it)
                }

                it.setOnClickListener { clickListener() }
            }

            thumbnailView = itemView.findViewById<ImageView>(R.id.media).apply {
                // Even thought we don't load animated image with ImageLoader, we still need to call it here so that postponed enter transition can be started
                imageLoader(item, this, ImageLoaderViewModel.TYPE_FULL)
                ViewCompat.setTransitionName(this, video.transitionName)
            }

            muteButton.setOnClickListener { toggleMute() }
        }

        // Need to call this so that exit transition can happen
        fun showThumbnail() { thumbnailView.visibility = View.INVISIBLE }

        fun hideControllers() { videoView.hideController() }
        fun setStopPosition(position: Long) {
            //Log.e(">>>","set stop position $position")
            stopPosition = position }

        // This step is important to reset the SurfaceView that ExoPlayer attached to, avoiding video playing with a black screen
        private fun resetVideoViewPlayer() { videoView.player = null }

        fun resume() {
            //Log.e(">>>>", "resume playback at $stopPosition")
            exoPlayer.apply {
                // Stop playing old video if swipe from it. The childDetachedFrom event of old VideoView always fired later than childAttachedTo event of new VideoView
                if (isPlaying) {
                    playWhenReady = false
                    stop()
                    oldVideoViewHolder?.apply {
                        if (this != this@VideoViewHolder) {
                            setStopPosition(currentPosition)
                            showThumbnail()
                        }
                    }
                }
                playWhenReady = true
                setMediaItem(MediaItem.Builder().setUri(videoUri).setMimeType(videoMimeType).build(), stopPosition)
                prepare()
                oldVideoViewHolder?.resetVideoViewPlayer()
                videoView.player = exoPlayer
                oldVideoViewHolder = this@VideoViewHolder

                // Maintain mute status indicator
                muteButton.setImageResource(if (exoPlayer.volume == 0f) R.drawable.ic_baseline_volume_off_24 else R.drawable.ic_baseline_volume_on_24)

                // Keep screen on
                (videoView.context as Activity).window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        fun pause() {
            //Log.e(">>>>", "pause playback")
            // If swipe out to a new VideoView, then no need to perform stop procedure. The childDetachedFrom event of old VideoView always fired later than childAttachedTo event of new VideoView
            if (oldVideoViewHolder == this) {
                exoPlayer.apply {
                    playWhenReady = false
                    stop()
                    setStopPosition(currentPosition)
                }
                hideControllers()
            }

            // Resume auto screen off
            (videoView.context as Activity).window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            savedPlayerState.setState(exoPlayer.volume == 0f, stopPosition)
        }

        private fun mute() {
            currentVolume = exoPlayer.volume
            exoPlayer.volume = 0f
            muteButton.setImageResource(R.drawable.ic_baseline_volume_off_24)
        }

        private fun toggleMute() {
            exoPlayer.apply {
                if (volume == 0f) {
                    volume = currentVolume
                    muteButton.setImageResource(R.drawable.ic_baseline_volume_on_24)
                }
                else mute()
            }
        }
    }

    fun initializePlayer(ctx: Context) {
        //private var exoPlayer = SimpleExoPlayer.Builder(ctx, { _, _, _, _, _ -> arrayOf(MediaCodecVideoRenderer(ctx, MediaCodecSelector.DEFAULT)) }) { arrayOf(Mp4Extractor()) }.build()
        exoPlayer = SimpleExoPlayer.Builder(ctx).build()
        currentVolume = exoPlayer.volume
        exoPlayer.addListener(object: Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                super.onPlaybackStateChanged(state)

                if (state == Player.STATE_ENDED) {
                    exoPlayer.playWhenReady = false
                    exoPlayer.seekTo(0L)
                    oldVideoViewHolder?.setStopPosition(0L)

                    // Resume auto screen off
                    (oldVideoViewHolder?.itemView?.context as Activity).window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                if (isPlaying) oldVideoViewHolder?.run {
                    showThumbnail()
                    hideControllers()
                }
            }
        })

        // Default mute the video playback during late night
        with(LocalDateTime.now().hour) {
            if (this >= 22 || this < 7) {
                currentVolume = exoPlayer.volume
                exoPlayer.volume = 0f
            }
        }
    }

    fun cleanUp() { exoPlayer.release() }

    fun setPlayerState(state: PlayerState) { savedPlayerState = state }
    fun getPlayerState(): PlayerState = savedPlayerState

    @Parcelize
    data class PlayerState(
        var isMuted: Boolean = false,
        var stopPosition: Long = FAKE_POSITION,
    ): Parcelable {
        fun setState(isMuted: Boolean, stopPosition: Long) {
            this.isMuted = isMuted
            this.stopPosition = stopPosition
        }
    }

    @Parcelize
    data class VideoItem(
        var uri: Uri,
        var mimeType: String,
        var width: Int,
        var height: Int,
        var transitionName: String,
    ): Parcelable

    companion object {
        private const val TYPE_PHOTO = 0
        private const val TYPE_ANIMATED = 1
        private const val TYPE_VIDEO = 2

        const val FAKE_POSITION = -1L
    }
}