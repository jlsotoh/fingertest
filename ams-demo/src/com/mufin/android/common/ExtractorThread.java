package com.mufin.android.common;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.mufin.ears.common.LicenseException;
import com.mufin.ears.signal.Signal;
import com.mufin.ears.signal.Signal.SignalLevel;
import com.mufin.ears.xtr.Extractor;
import com.mufin.ears.xtr.Fingerprint;


public class ExtractorThread 
extends BlockingQueueThread 
implements ExtractorTask {

	private volatile int sampleRate = -1;
	private volatile int channels = -1;
	private volatile int blocksize = -1;
	private Extractor xtr = null;
	
	/** 
	 * The global sample counter to calculate the query position in ms.<br/>
	 * Sample counter is increased when samples pushed to extractor and 
	 * used for query position ms when fingerprint full event 
	 */
	private volatile long samplesCount = -1;
	private volatile int queryDuration = -1;
    private volatile float overlap = 0.f;
    private volatile int overlapInFrames = -1;

	private static final String PARAM_CODE = "code";
	private static final String PARAM_SAMPLES = "samples";
	
	private static final int MSG_FP_FULL = 1;
	private static final int MSG_ERROR = 2;
	private static final String MSG_KEY_FP = "fingerprint";
	private static final String MSG_KEY_POS = "fingerprintPos";
	private static final String MSG_KEY_MSG = "message";
	private static final String MSG_KEY_MSG_CODE = "code";
	
	public ExtractorThread( int queryDuration, float overlap, int sampleRate, int channels ) throws LicenseException {
		super("ExtractorThread");
		
		if( sampleRate <= 0 ) {
			publishError( "invalid sampleRate", null );
			return;
		}
		if( channels <= 0 ) {
			publishError( "invalid number of channels", null );
			return;
		}
		
		this.queryDuration = queryDuration;
		this.overlap = overlap;
		this.sampleRate = sampleRate;
		this.channels = channels;
		xtr = new Extractor( sampleRate, channels );
		blocksize = xtr.granularity();
	}
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void start( Listener listener ) {
		super.start( new Handler( new TaskHandlerCallback(listener) ) );
	}
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void cancel() {
		setCancelled( true );
		
		Bundle bundle = new Bundle();
		bundle.putSerializable( PARAM_CODE, ParamCode.cancel );
		
		getQueue().offer( bundle );
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void putSamples( short[] samples )
	{
		Bundle bundle = new Bundle();
		bundle.putSerializable( PARAM_CODE, ParamCode.add_samples );
		bundle.putShortArray( PARAM_SAMPLES, samples );
		
		getQueue().offer( bundle );
	}
	
	@Override
	public void run() {
		if( xtr == null ) {
			publishError( "extractor not initialized", null );
			return;
		}

		Fingerprint fingerprint = new Fingerprint( "0", queryDuration );
		xtr.assign(fingerprint);
		
        // get overlap in frames
        overlapInFrames = ( int ) Math.floor( overlap * fingerprint.capacity() );

        samplesCount = 0;
        short[] samplesBlock = new short[ blocksize ];
		short[] carrySamples = new short[ blocksize ];
		int carrySamplesCount = 0;
		
		Bundle queueElement;
		running : while(!isCancelled())
		{
			try
			{ // Take element from queue. Blocks when empty.
				queueElement = getQueue().take();
			}
			catch(InterruptedException e)
			{
				break running;
			}

			if(isCancelled())
				break running;

			// the param code shows the type of the queue element
			switch((ParamCode)queueElement.getSerializable( PARAM_CODE ))
			{
				case add_samples:
					short[] samples = queueElement.getShortArray( PARAM_SAMPLES );
					// check the RMS of this audio and remember it for next step
					Signal signal = new Signal();
					SignalLevel rmsStatus = signal.amplify( samples, sampleRate );
					if( rmsStatus == SignalLevel.TooLow ) {
						Log.i(getClass().getName(), "Signal level too low" );
					}/* else if(rmsStatus == SignalLevel.TooHigh) {
						Log.i(getClass().getName(), "Signal level too high" );
					}
					*/
					
	                int numBlocks = ( samples.length + carrySamplesCount ) / blocksize;
	                int remainBlocks = numBlocks;
	                
	                // processed samples counter of current sample buffer array
	                int processedSamples = 0;
	                // for best performance, the sample buffer should be a multiple of extractor granularity
	        		// e.g. 
	        		// granularity is 480 samples @ 16000Hz sampling rate
	        		// samples length should be n * 480
	                for( ; remainBlocks > 0; remainBlocks-- )
	                {
	                	// check, if some samples from previous push need to be fingerprinted
	                	// before new samples are pushed
	                	if( processedSamples == 0 && carrySamplesCount > 0 )
	                	{
	                		System.arraycopy( carrySamples, 0, samplesBlock, 0, carrySamplesCount );
	                		int offset =  blocksize - carrySamplesCount;
	                		System.arraycopy( samples, 0, samplesBlock, carrySamplesCount, offset );
	                		
	                		processedSamples += offset;
	                		carrySamplesCount = 0;
	                	}
	                	else // normal samples copy block
	                	{
	                		System.arraycopy( samples, processedSamples, samplesBlock, 0, blocksize );
	                		processedSamples += blocksize;
	                	}
	        			int returnCode = 0;
                        try
                        {
	                        returnCode = xtr.push( samplesBlock );
                        }
                        catch( LicenseException e )
                        {
	        				publishError( "License error", -1 );
                        }

	        			// returnCode = 0 -> ok, frame added to fingerprint
	        			// returnCode = 1 -> full, fingerprint filled
	        			if( returnCode < 0 )
	        			{
	        				publishError( "Error while adding samples.", returnCode );
	        				break;
	        			}
						
	        			samplesCount += blocksize;
						
	        			if( returnCode == 1 ) // ok, fingerprint is full
	        			{
							long fingerprintPosition = (long)(1000.f * samplesCount / ( sampleRate * channels ) - fingerprint.duration());
							notifyFingerprintFull( fingerprint, fingerprintPosition );
							
	        				if( overlapInFrames > 0 )
	        				{
								// create next fingerprint 
	        					Fingerprint nextFingerprint = new Fingerprint( "0", queryDuration );
	        					
	        					// fill with overlapping frames from current fingerprint
	        					long queryDurationInFrames = fingerprint.capacity();
	        					long start = queryDurationInFrames - overlapInFrames;
	        					nextFingerprint.appendFrames( fingerprint, start, overlapInFrames );
	        					
	        					// assign fingerprint
	        					fingerprint = nextFingerprint;
	        				}
	        				else
	        				{
		        				// create new fingerprint after sending to listener
								fingerprint = new Fingerprint( "0", queryDuration );
	        				}
							xtr.assign( fingerprint );
							
							Log.d(getClass().getName(), "fingerprint full, pushed "+(numBlocks - remainBlocks)+
														" sample blocks, push remaining "+remainBlocks+
														" blocks to new fingerprint" );
	        			}
	                }
	                
	                // sample buffer doesn't fit granularity block size
	                // remember remaining samples for next sample buffer push
	                int remainSamples = samples.length - processedSamples;
	                if( remainSamples > 0 )
	                {
	                	System.arraycopy( samples, processedSamples, carrySamples, 0, remainSamples );
	                	carrySamplesCount = remainSamples;
	                }
	                else
	                {
	                	carrySamplesCount = 0;
	                }
	                
	                //Log.d(getClass().getName(), "pushed "+numBlocks+" sample blocks to fingerprint, "+remainSamples+" samples stored for next push" );
	                
					break;
				case cancel:
					getQueue().clear();
					this.setCancelled( true );
					break running;
				default:
					publishError( "Unknown message in queue.", null );
					break running;
			}
		}
		
		getQueue().clear();
		
		Extractor.destroy(xtr);
		// cleanup remaining fingerprint objects by garbage collector
		
		Log.i( getClass().getName(), "Extractor Task ended" );
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getXtrVersion() {
		return "Fingerprint " + Fingerprint.version() +
				"; Extractor " + Extractor.version();
	}
	
	/**
	 * publish error message to observer/listener
	 * @param errorMessage the string error message
	 * @param errorCode the optional error code
	 */
	private void publishError( String errorMessage, Integer errorCode )
	{
		Log.e( getClass().getName(), "publishError("+errorMessage+", "+errorCode+")" );
		
		Bundle bundle = new Bundle();
		bundle.putString( MSG_KEY_MSG, errorMessage );
		if( errorCode != null ) {
			bundle.putInt( MSG_KEY_MSG_CODE, errorCode );
		}
		
		sendMessage( MSG_ERROR, bundle );
	}
	/**
	 * publish result to observer/listener
	 * @param result the result object of identification or null if not found
	 */
	private void notifyFingerprintFull( Fingerprint fp, long fingerprintPosition )
	{
		Bundle bundle = new Bundle();
		bundle.putSerializable( MSG_KEY_FP, new BundleObject<Fingerprint>(fp) );
		bundle.putLong( MSG_KEY_POS, fingerprintPosition );
		
		sendMessage( MSG_FP_FULL, bundle );
	}
	
	/**
	 * handler for listener messages
	 */
	private class TaskHandlerCallback implements Handler.Callback
	{
		private Listener listener;

		public TaskHandlerCallback( Listener observer )
		{
			this.listener = observer;
		}

		@Override
		public boolean handleMessage( Message msg )
		{
			Bundle data = msg.getData();
        	
            switch( msg.what )
			{
			case MSG_FP_FULL:
				listener.onFingerprintFull(
							ExtractorThread.this,
							((BundleObject<Fingerprint>)data.get( MSG_KEY_FP )).obj,
							data.getLong(MSG_KEY_POS) );
				break;
			case MSG_ERROR:
				Object errorCode = data.get( MSG_KEY_MSG_CODE );
				listener.onExtractorError( 
							ExtractorThread.this,
							data.getString( MSG_KEY_MSG ),
							errorCode == null ? null : (Integer) errorCode );
				break;

			default:
				Log.e( getClass().getName(), "This should never happen: unknown progress message." );
				throw new RuntimeException( "This should never happen: unknown progress message." );
			}
			return false;
		}
	}
}
