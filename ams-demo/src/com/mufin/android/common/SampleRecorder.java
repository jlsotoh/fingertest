/*
 * Copyright (C) mufin GmbH. All rights reserved.
 */
package com.mufin.android.common;

import java.util.EventListener;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * Records audio samples.
 */
public class SampleRecorder extends Thread
{
    // internal message types and params
    private static final int MSG_ON_RECORDER_INIT = 1;
    private static final int MSG_ON_RECORDER_INIT_ERROR = 2;
	private static final int MSG_ON_RECORDER_UPDATE = 3;
	private static final int MSG_ON_RECORDED = 4;
	private static final int MSG_ON_RECORDER_FINISHED = 5;

    private static final String PARAM_SAMPLE_RATE = "SAMPLE_RATE";
    private static final String PARAM_CHANNELS = "CHANNELS";
    private static final String PARAM_SAMPLES = "SAMPLES";
    private static final String PARAM_SAMPLES_COUNT = "SAMPLES_COUNT";
    
	public static final int RECORDING_DURATION_UNLIMITED = -1;
    // minimum recording buffer size
    public static final int MIN_BUFFER_SIZE = 2048;
    
    //http://developer.android.com/reference/android/media/AudioRecord.html#AudioRecord%28int,%20int,%20int,%20int,%20int%29
    public static final int SAMPLE_RATE; // set in static block, platform dependent
    // mic channel config
    public static final int CHANNEL_CONFIGURATION = AudioFormat.CHANNEL_IN_MONO;
    // number of channels in CHANNEL_CONFIGURATION
    public static final int CHANNEL_CONFIGURATION_COUNT = 1;
    public static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    /** sample count from offset milisecs */
    private int offsetSamples;
    /** required sample count of recording duration */
    private int requiredSamples;

	/** indentify retry counter */
	private int tries;
	
    /** the recording buffer size */
    private int bufferSize;
    /** delay sample count of recording duration */
    private int delaySamples;

	/** the recording duration for one query in ms */
    private final int queryDuration;
	/** the maximum recording duration in ms */
	private final int maxDuration;
    /** audio delay in ms */
    private int delayDuration;

    /** the handler class to send listener messages */
    private Handler observer = null;
    /** flag to cancel thread loop */
    private boolean canceled = false;
    
    static {
        if(Build.PRODUCT.contains("sdk")) // product = "sdk" is emulator
            SAMPLE_RATE = 8000; // emulator only supports 8kHz recording
        else
            SAMPLE_RATE = 16000;
    }

    /**
     * ctor
     * @param queryDuration the recording duration in ms
	 * @param maxDuration the maximum recording duration in ms
     * @param delayDuration the delay duration in ms
     * @param recorderListener the callback listener for recorder messages
     */
    public SampleRecorder( int queryDuration, 
    						int maxDuration, int delayDuration,
    						Listener recorderListener )
    {
        super();
        
        observer = new Handler( new RecorderHandlerCallback( recorderListener ) );

        this.queryDuration = queryDuration;
		
		if( maxDuration == RECORDING_DURATION_UNLIMITED )
			this.maxDuration = Integer.MAX_VALUE;
		else
			this.maxDuration = maxDuration;
		
        this.delayDuration = delayDuration;
    }

    @Override
    public void run()
    {
        Log.d( getClass().getName(), "run" );
        
        // init android audio recorder
        final AudioRecord recorder;
        if( (recorder = init()) == null )
        {
            sendMessage( MSG_ON_RECORDER_INIT_ERROR, null );
            return;
        }

        Log.d( getClass().getName(), "start recording" );
        // begin recording
        recorder.startRecording();

        int samplesCount = 0;
        int remainSamples, readSamples, samplesRead;
        int delay = delaySamples;
        
        final short[] readBuf = new short[ bufferSize ];
//        Log.d( getClass().getName(), "read buffer size bufferSize: " + bufferSize );
        int circularBufferSize = requiredSamples + delaySamples;
//        Log.d( getClass().getName(), "create circular buffer circularBufferSize:" + circularBufferSize );
        
        final CircularShortBuffer samplesBuffer = new CircularShortBuffer( circularBufferSize );
		
		int x = 0;
        
        // the main loop for continuous or not
        recording: do
        {
			// read until desired samples recorded + offset
            while( !canceled &&
                    (remainSamples = requiredSamples + delay - samplesCount) > 0 )
            {
                readSamples = remainSamples < bufferSize ? remainSamples : bufferSize;

                // fill sample buffer from "startSample" to "readSamples" length
                // "startSample" will stay 0 until offset isn't reached, so samplebuffer is overwritten
                samplesRead = recorder.read( readBuf, 0, readSamples );
                
//                Log.d( getClass().getName(), "read " + samplesRead + " samples from recorder. want to get " + readSamples + " samples. bufferSize: " + bufferSize );
                
                if( samplesRead < 0 )
                {
                    Log.e( getClass().getName(), "error " + samplesRead + " while reading samples." );
                    break recording;
                }
                if( samplesRead == 0 )
                {
                    Log.v( getClass().getName(), "no more samples." );
                    break recording;
                }
    
                samplesCount += samplesRead;
    
//                Log.d( getClass().getName(), "add samples " + samplesRead + " to circular buffer" );
                samplesBuffer.put( readBuf, 0, samplesRead );
				//Log.d( getClass().getName(), "[time] recorder update: " + samplesRead + " samples available" );
                publishProgressRecorderUpdate( readBuf, samplesRead );
            }
            
			x++;
			
            // send recorder finished a period
            Bundle bundle = new Bundle();
//            Log.d( getClass().getName(), "finished get buffer samplesBuffer.size: " + samplesBuffer.getSize() + " delaySamples: " + delaySamples + " diff: " + (samplesBuffer.getSize() - delaySamples) );
            bundle.putShortArray( PARAM_SAMPLES, samplesBuffer.getBuffer(0, samplesBuffer.getSize() - delaySamples) );
			if(x < tries)
				sendMessage( MSG_ON_RECORDED, bundle );
			else
				sendMessage( MSG_ON_RECORDER_FINISHED, bundle );
			Log.d( getClass().getName(), "recording x: "+x+" tries:"+tries );

            samplesCount = delay = 0;
        } while( !canceled && x < tries ); // next iteration if continuous mode on

        Log.d( getClass().getName(), "stop recording" );
        recorder.stop();
        recorder.release();

        return;
    }

    public AudioRecord init()
    {
        Log.d( getClass().getName(), "init" );

        // calculate minimum audio buffer size
        bufferSize = AudioRecord.getMinBufferSize(
        		SAMPLE_RATE, CHANNEL_CONFIGURATION, AUDIO_ENCODING );
        if( bufferSize < 0 )
        {
        	Log.e( getClass().getName(), "getMinBufferSize() failed with error " + bufferSize );
            
            return null;
        }
//        Log.d( getClass().getName(), "bufferSize " + bufferSize );
        // too small buffersize result in short refresh event period
        bufferSize = Math.max( bufferSize, MIN_BUFFER_SIZE );

        // calc needed samples from recording duration
        requiredSamples = (int)(SAMPLE_RATE * ((float)queryDuration / 1000f));
        delaySamples = (int)(SAMPLE_RATE * ((float)delayDuration / 1000f));
        
		tries = (int)(((float)maxDuration / (float)queryDuration) + .5f);
		Log.d( getClass().getName(), "queryDuration: " + queryDuration + " maxDuration: " + maxDuration + " tries: " + tries );
		
        Log.d( getClass().getName(), "instanciate new AudioRecord" );

        // init the android audio recorder from microphone
        AudioRecord recorder = new AudioRecord( MediaRecorder.AudioSource.MIC, 
								        		SAMPLE_RATE,
												CHANNEL_CONFIGURATION, 
												AUDIO_ENCODING, bufferSize * 10 );

        Log.d( getClass().getName(), "samplerate " + recorder.getSampleRate() + 
                                    " channelconfig: " + recorder.getChannelConfiguration() + 
                                    " audioformat: " + recorder.getAudioFormat() );
        
//        Log.d( getClass().getName(), "bufferSize after init " + AudioRecord.getMinBufferSize(
//                                                                    recorder.getSampleRate(), 
//                                                                    recorder.getChannelConfiguration(), 
//                                                                    recorder.getAudioFormat()) );
        
        if( recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED )
        {
            Log.e( getClass().getName(), "can't initialize audio recording." );
            sendMessage( MSG_ON_RECORDER_INIT_ERROR, null );
            
            return null;
        }
        
        // inform listener about init done
        publishProgressInit( recorder.getSampleRate(), recorder.getChannelCount() );

        return recorder;
    }
    /**
     * publish init message to observer/listener
     * @param sampleRate the audio recorder sample rate
     * @param channels the audio recorder channel count
     */
    private void publishProgressInit( int sampleRate, int channels )
    {
        Bundle bundle = new Bundle();
        bundle.putInt( PARAM_SAMPLE_RATE, sampleRate );
        bundle.putInt( PARAM_CHANNELS, channels );

        sendMessage( MSG_ON_RECORDER_INIT, bundle );
    }
    /**
     * publish buffer recorded message to observer/listener
     * @param samples the sample buffer
     * @param samplesCount the length of the filled buffer
     */
	private void publishProgressRecorderUpdate( short[] samples, int samplesCount )
    {
        Bundle bundle = new Bundle();
        bundle.putShortArray( PARAM_SAMPLES, samples );
        bundle.putInt( PARAM_SAMPLES_COUNT, samplesCount );

		sendMessage( MSG_ON_RECORDER_UPDATE, bundle );
    }

    /**
     * send message to observer
     * @param what Value to assign to the what member. (see: {@link Message#obtain(Handler, int)})
     * @param data the message content
     */
    private void sendMessage( int what, Bundle data )
    {
        Message message = Message.obtain( observer, what );
        if( data != null )
            message.setData( data );
        observer.sendMessage( message );
    }
    
    /**
     * cancel recording if running
     */
    public void cancel()
    {
        canceled = true;
    }
    
    /**
     * @return the calculated buffer size of the audio recorder
     */
    public int getBufferSize() {
        return bufferSize;
    }

    /**
     * the listener for recorder events
     */
    public interface Listener extends EventListener
    {
        /**
         * audio recorder successfully inited
         * @param sampleRate the audio recorder sampling rate
         * @param channels the audio recorder channel config
         */
        public void onRecorderInit( SampleRecorder source, int sampleRate, int channels );
		/** message during sample recording to update recording status */
        public void onRecorderUpdate( SampleRecorder source, short[] samples, int samplesRecordedCount );
		/** sample buffer filled and ready to identify, will be repeated until maxDuration reached */
		public void onRecorded( SampleRecorder source, short[] samples );
		/** alternate message to onRecorded(), if recorder reached maxDuration and will stop */
        public void onRecorderFinished( SampleRecorder source, short[] samples );
        /** recorder error */
        public void onRecorderInitError( SampleRecorder source );
    }

    /**
     * handler for recorder messages
     */
    private class RecorderHandlerCallback implements Handler.Callback
    {
        private Listener listener;

        public RecorderHandlerCallback( Listener observer )
        {
            this.listener = observer;
        }

        @Override
        public boolean handleMessage( Message msg )
        {
        	Bundle data = msg.getData();
        	
            switch( msg.what )
            {
            case SampleRecorder.MSG_ON_RECORDER_INIT:
                listener.onRecorderInit( 
                			SampleRecorder.this, 
	                		data.getInt( PARAM_SAMPLE_RATE ),
	                        data.getInt( PARAM_CHANNELS ) );
                break;

			case SampleRecorder.MSG_ON_RECORDER_UPDATE:
                listener.onRecorderUpdate( 
                			SampleRecorder.this, 
	                		data.getShortArray( PARAM_SAMPLES ),
	                        data.getInt( PARAM_SAMPLES_COUNT ) );
                break;

            case SampleRecorder.MSG_ON_RECORDER_INIT_ERROR:
                listener.onRecorderInitError( SampleRecorder.this );
                break;

			case SampleRecorder.MSG_ON_RECORDED:
				listener.onRecorded( 
							SampleRecorder.this, 
                			data.getShortArray( PARAM_SAMPLES ) );
				break;

            case SampleRecorder.MSG_ON_RECORDER_FINISHED:
				listener.onRecorderFinished( 
							SampleRecorder.this, 
                			data.getShortArray( PARAM_SAMPLES ) );
                break;
            }
            return false;
        }
    }
}
