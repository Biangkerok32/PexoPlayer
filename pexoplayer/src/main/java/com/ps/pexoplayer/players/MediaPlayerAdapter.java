package com.ps.pexoplayer.players;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

public class MediaPlayerAdapter extends PlayerAdapter {

    private static final String TAG = "MediaPlayerAdapter";

    private final Context mContext;
    private MediaMetadataCompat mCurrentMedia;
    private boolean mCurrentMediaPlayedToCompletion;
    private int mState;
    private long mStartTime;
    private PlaybackInfoListener mPlaybackInfoListener;

    // ExoPlayer objects
    private SimpleExoPlayer mExoPlayer;
    private TrackSelector mTrackSelector;
    private DefaultRenderersFactory mRenderersFactory;
    private DataSource.Factory mDataSourceFactory;
    private ExoPlayerEventListener mExoPlayerEventListener;
    private Handler handler = new Handler();
    private Runnable runnable;

    private boolean trackUpdated = false;
    private boolean isRepeatEnabled = false;
    private boolean isShuffleEnabled = false;

    public MediaPlayerAdapter(Context context, PlaybackInfoListener playbackInfoListener) {
        super(context);
        mContext = context.getApplicationContext();
        mPlaybackInfoListener = playbackInfoListener;
    }

    private void initializeExoPlayer() {
        if (mExoPlayer == null) {
            mTrackSelector = new DefaultTrackSelector();
            mRenderersFactory = new DefaultRenderersFactory(mContext);
            mDataSourceFactory = new DefaultDataSourceFactory(mContext, Util.getUserAgent(mContext, "AudioStreamer"));
            mExoPlayer = ExoPlayerFactory.newSimpleInstance(mContext, mRenderersFactory, mTrackSelector, new DefaultLoadControl());

            if (mExoPlayerEventListener == null) {
                mExoPlayerEventListener = new ExoPlayerEventListener();
            }
            mExoPlayer.addListener(mExoPlayerEventListener);
        }
    }

    @Override
    protected void onPlay() {
        if (mExoPlayer != null && !mExoPlayer.getPlayWhenReady()) {
            mExoPlayer.setPlayWhenReady(true);
            setNewState(PlaybackStateCompat.STATE_PLAYING);
        }
    }

    @Override
    protected void onPause() {
        if (mExoPlayer != null && mExoPlayer.getPlayWhenReady()) {
            mExoPlayer.setPlayWhenReady(false);
            setNewState(PlaybackStateCompat.STATE_PAUSED);
        }
    }

    public final void setRepeat(int repeatMode) {
        isRepeatEnabled = (repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE);
    }

    public final void setShuffle(int shuffleMode) {
        isShuffleEnabled = (shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL);
    }

    @Override
    public void playFromMedia(MediaMetadataCompat metadata) {
        trackUpdated = true;
        if (runnable != null) {
            handler.removeCallbacks(runnable);
        }
        handler.removeCallbacksAndMessages(null);
        startTrackingPlayback();
        playFile(metadata);
    }

    @Override
    public MediaMetadataCompat getCurrentMedia() {
        return mCurrentMedia;
    }

    @Override
    public boolean isPlaying() {
        return mExoPlayer != null && mExoPlayer.getPlayWhenReady();
    }

    @Override
    protected void onStop() {
        // Regardless of whether or not the ExoPlayer has been created / started, the state must
        // be updated, so that MediaNotificationManager can take down the notification.
        // Log.d(TAG, "onStop: stopped");
        setNewState(PlaybackStateCompat.STATE_STOPPED);
        release();
    }

    @Override
    public void seekTo(long position) {
        if (mExoPlayer != null) {
            mExoPlayer.seekTo((int) position);

            // Set the state (to the current state) because the position changed and should
            // be reported to clients.
            setNewState(mState);
        }
    }

    @Override
    public void setVolume(float volume) {
        if (mExoPlayer != null) {
            mExoPlayer.setVolume(volume);
        }
    }

    private void release() {
        if (mExoPlayer != null) {
            mExoPlayer.release();
            mExoPlayer = null;
        }
    }

    /**
     * Plays a file from MediaMetadataCompat
     *
     * @param metaData MediaMetadataCompat
     */
    private void playFile(MediaMetadataCompat metaData) {
        String mediaId = metaData.getDescription().getMediaId();
        boolean mediaChanged = (mCurrentMedia == null
                || !mediaId.equals(mCurrentMedia.getDescription().getMediaId()));

        if (mCurrentMediaPlayedToCompletion) {
            // Last audio file was played to completion, the resourceId hasn't changed, but the
            // player was released, so force a reload of the media file for playback.
            mediaChanged = true;
            mCurrentMediaPlayedToCompletion = false;
        }

        if (isRepeatEnabled && !mediaChanged) {
            release();
        } else {
            if (!mediaChanged) {
                if (!isPlaying()) {
                    play();
                }
                return;
            } else {
                release();
            }
        }

        mCurrentMedia = metaData;

        initializeExoPlayer();

        try {
            MediaSource audioSource =
                    new ExtractorMediaSource.Factory(mDataSourceFactory)
                            .createMediaSource(Uri.parse(metaData.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI)));
            mExoPlayer.prepare(audioSource);
            //  Log.d(TAG, "onPlayerStateChanged: PREPARE");
        } catch (Exception e) {
            throw new RuntimeException("Failed to play media uri: "
                    + metaData.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI), e);
        }

        play();
    }

    /**
     * Sends onSeekTo() and onPlaybackComplete()
     * event updates in "PlaybackInfoListener" interface
     */
    public void startTrackingPlayback() {
        Log.d(TAG, "startTrackingPlayback ");
        runnable = new Runnable() {
            @Override
            public void run() {
                if (mExoPlayer != null) {
                    if (isPlaying()) {
                        mPlaybackInfoListener.onBufferedTo(
                                mExoPlayer.getBufferedPosition()
                        );
                        mPlaybackInfoListener.onSeekTo(
                                mExoPlayer.getContentPosition(), mExoPlayer.getDuration()
                        );
                        handler.postDelayed(this, 100);
                    }

                    if (mExoPlayer.getContentPosition() >= mExoPlayer.getDuration()
                            && mExoPlayer.getDuration() > 0) {
                        if (trackUpdated) {
                            trackUpdated = false;
                            mPlaybackInfoListener.onPlaybackComplete();
                        }
                    }
                }
            }
        };
        handler.postDelayed(runnable, 100);
    }

    /**
     * This is the main reducer for the player state machine.
     *
     * @param newPlayerState @PlaybackStateCompat.State
     */
    private void setNewState(@PlaybackStateCompat.State int newPlayerState) {
        mState = newPlayerState;

        // Whether playback goes to completion, or whether it is stopped, the
        // mCurrentMediaPlayedToCompletion is set to true.
        if (mState == PlaybackStateCompat.STATE_STOPPED) {
            mCurrentMediaPlayedToCompletion = true;
        }

        final long reportPosition = mExoPlayer == null ? 0 : mExoPlayer.getCurrentPosition();

        // Send playback state information to service
        publishStateBuilder(reportPosition);
    }

    /**
     * Sends onPlaybackStateChange() and updatePlayerView()
     * event updates in "PlaybackInfoListener" interface
     *
     * @param reportPosition long reportPosition
     */
    private void publishStateBuilder(long reportPosition) {
        final PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder();
        stateBuilder.setActions(getAvailableActions());
        stateBuilder.setState(mState,
                reportPosition,
                1.0f,
                SystemClock.elapsedRealtime());
        mPlaybackInfoListener.onPlaybackStateChange(stateBuilder.build());
        if (mCurrentMedia != null) {
            mPlaybackInfoListener.updateUI(mCurrentMedia.getDescription().getMediaId());
        }
    }

    /**
     * Set the current capabilities available on this session. Note: If a capability is not
     * listed in the bitmask of capabilities then the MediaSession will not handle it. For
     * example, if you don't want ACTION_STOP to be handled by the MediaSession, then don't
     * included it in the bitmask that's returned.
     */
    @PlaybackStateCompat.Actions
    private long getAvailableActions() {
        long actions = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;

        switch (mState) {
            case PlaybackStateCompat.STATE_STOPPED:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE;
                break;
            case PlaybackStateCompat.STATE_PLAYING:
                actions |= PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_SEEK_TO;
                break;
            case PlaybackStateCompat.STATE_PAUSED:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_STOP;
                break;
            default:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PAUSE;
        }
        return actions;
    }

    private class ExoPlayerEventListener implements Player.EventListener {

        @Override
        public void onTimelineChanged(Timeline timeline, Object manifest, int reason) {

        }

        @Override
        public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {

        }

        @Override
        public void onLoadingChanged(boolean isLoading) {

        }

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            switch (playbackState) {
                case Player.STATE_ENDED: {
                    setNewState(PlaybackStateCompat.STATE_PAUSED);
                    break;
                }
                case Player.STATE_BUFFERING: {
                    // Log.d(TAG, "onPlayerStateChanged: BUFFERING");
                    setNewState(PlaybackStateCompat.STATE_BUFFERING);
                    mStartTime = System.currentTimeMillis();
                    break;
                }
                case Player.STATE_IDLE: {
                    setNewState(PlaybackStateCompat.STATE_NONE);
                    break;
                }
                case Player.STATE_READY: {
                    setNewState(PlaybackStateCompat.STATE_PLAYING);
                    // Log.d(TAG, "onPlayerStateChanged: READY");
                    // Log.d(TAG, "onPlayerStateChanged: TIME ELAPSED: " + (System.currentTimeMillis() - mStartTime));
                    break;
                }
            }
        }

        @Override
        public void onRepeatModeChanged(int repeatMode) {
            //   Log.e(TAG, "onRepeatModeChanged: " + repeatMode);
        }

        @Override
        public void onShuffleModeEnabledChanged(boolean onShuffleModeEnabledChanged) {
            //   Log.e(TAG, "onShuffleModeEnabledChanged: " + onShuffleModeEnabledChanged);
        }

        @Override
        public void onPlayerError(ExoPlaybackException error) {

        }

        @Override
        public void onPositionDiscontinuity(int reason) {

        }

        @Override
        public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {

        }

        @Override
        public void onSeekProcessed() {

        }
    }

}

























