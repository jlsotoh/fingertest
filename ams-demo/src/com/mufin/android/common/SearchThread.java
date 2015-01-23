/*
 * Copyright (C) mufin GmbH. All rights reserved.
 */
package com.mufin.android.common;

import java.util.ArrayList;
import java.util.List;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.mufin.ears.common.IdentifyResult;
import com.mufin.ears.common.LicenseException;
import com.mufin.ears.xtr.Fingerprint;

/**
 * Thread implementation of {@link SearchTask}
 */
public class SearchThread 
extends BlockingQueueThread 
implements SearchTask
{
	private static final int MAX_RESULTS = 10;

	// internal queue params
	private static final String PARAM_CODE = "PARAM_CODE";
	private static final String PARAM_FINGERPRINT = "PARAM_FINGERPRINT";
	private static final String PARAM_FINGERPRINT_POS = "PARAM_FINGERPRINT_POS";
	
	public static final int MessageCodeError = 1;
	public static final int MessageCodeResult = 2;

	private static final String PROGRESS_RESULT = "RESULT";
	private static final String PROGRESS_SEARCH_DURATION = "SEARCH_DURATION";
	private static final String PROGRESS_MESSAGE = "MESSAGE";
	private static final String PROGRESS_ERROR_CODE = "ERROR_CODE";

	/** the current thread loop process status */
	private volatile StatusCode status = StatusCode.notInitialized;

	private SearchWorker worker;

	public SearchThread()
	{
		super("SearchThread");
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void start( SearchWorker worker, Listener listener ) {
		this.worker = worker;
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

	@Override
	public void run()
	{
		// no connection data, no identify
		if(worker == null) {
			publishError( "search worker not set" );
			return;
		}
		
		status = StatusCode.free;
		
		Bundle queueElement;
		session : while( !isCancelled() )
		{
			try
			{ // Take element from queue. Blocks when empty.
				queueElement = getQueue().take();
			}
			catch( InterruptedException e )
			{
				break session;
			}

			if( isCancelled() )
				break session;

			// the param code shows the type of the queue element
			switch( (ParamCode) queueElement.getSerializable( PARAM_CODE ) )
			{
			case identify: // end of record: try to identify
				Log.d( getClass().getName(), "end of record" );
				
				@SuppressWarnings("unchecked")
				Fingerprint fingerprint = ((BundleObject<Fingerprint>)queueElement.getSerializable( PARAM_FINGERPRINT )).obj;
				if( fingerprint == null || !fingerprint.full() )
				{
					publishError( "Not enough data for identification." );
					break;
				}
				else
				{
					status = StatusCode.identifying;
					
					long start = System.currentTimeMillis();
					long queryPosition = queueElement.getLong( PARAM_FINGERPRINT_POS );
					
					List<IdentifyResult> result = new ArrayList<IdentifyResult>( MAX_RESULTS );
					int searchRes;
                    try
                    {
	                    searchRes = worker.search(fingerprint, queryPosition, MAX_RESULTS, result);
                    }
                    catch( LicenseException e )
                    {
						publishError( "license error", -1 );
						break session;
                    }
                    
                    // cancelled while waiting for result?
                    if(!isCancelled())
                    {
						if(searchRes < 0)
						{
							publishError( "session search error", searchRes );
							Log.e( getClass().getName(), "session search error after " + ((System.currentTimeMillis() - start) + "ms" ) );
						}
						else
						{
							long identTime = (System.currentTimeMillis() - start);
							
							// copy results from iterator, because iterator becomes invalid, with next predator.push()
							ArrayList<IdentifyResult> results = new ArrayList<IdentifyResult>();
							for (IdentifyResult hyp : result) {
								Log.d( getClass().getName(), "worker.search result: " + hyp );
								if(hyp != null)
								{
									Log.d( getClass().getName(), "result id: " + hyp.getId() +
																" conf: " + hyp.getConfidence() +
																" ts: " + hyp.getTimestamp() );
									results.add( hyp );
								}
							}
							
							// send result message
							publishResult( results, identTime );
						}
						
						// reset status after identification
						status = StatusCode.free;
                    }
				}
				break;
				
			case cancel: // end the task and release the queue
				getQueue().clear();
				this.setCancelled( true );
				break session;

			default:
				publishError( "Unknown message in queue." );
			}
		}
		
		getQueue().clear();
		
		worker.destroy();
		worker = null; // de-ref worker instance
		
		status = StatusCode.notInitialized;

		Log.i( getClass().getName(), "Search Task ended" );
	}
	
	/**
	 * publish error message to observer/listener
	 * @param errorMessage the string error message
	 * @param errorCode the optional error code
	 */
	private void publishError( String errorMessage, Integer errorCode )
	{
		status = StatusCode.error;
		
		Log.e( getClass().getName(), "publishError("+errorMessage+", "+errorCode+")" );
		
		Bundle bundle = new Bundle();
		bundle.putString( PROGRESS_MESSAGE, errorMessage );
		if( errorCode != null ) {
			bundle.putInt( PROGRESS_ERROR_CODE, errorCode );
		}
		
		sendMessage( MessageCodeError, bundle );
	}
	/**
	 * publish error message to observer/listener
	 * @param errorMessage the string error message
	 */
	private void publishError( String errorMessage )
	{
		publishError( errorMessage, null );
	}
	/**
	 * publish result to observer/listener
	 * @param results the result object of identification or null if not found
	 */
	private void publishResult( ArrayList< IdentifyResult > results, long searchTime )
	{
		Bundle bundle = new Bundle();
		bundle.putSerializable( PROGRESS_RESULT, results );
		bundle.putLong( PROGRESS_SEARCH_DURATION, searchTime );
		
		sendMessage( MessageCodeResult, bundle );
	}
	
	/* ########################
	 * methods for external control
	 * ######################## */

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void putFingerprint( Fingerprint fp, long queryPosition )
	throws IllegalStateException
	{
		if(status == StatusCode.identifying) {
			throw new IllegalStateException("Cannot put samples while previous identification running");
		}
		
		Bundle bundle = new Bundle();
		bundle.putSerializable( PARAM_CODE, ParamCode.identify );
		bundle.putSerializable( PARAM_FINGERPRINT, new BundleObject<Fingerprint>(fp) );
		bundle.putLong( PARAM_FINGERPRINT_POS, queryPosition );
		
		getQueue().offer( bundle );
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public StatusCode getEarsStatus() {
		return status;
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

			case MessageCodeResult:
				@SuppressWarnings("unchecked")
				ArrayList<IdentifyResult> identifyResult = (ArrayList<IdentifyResult>) data.get( PROGRESS_RESULT );
				long searchDuration = data.getLong(PROGRESS_SEARCH_DURATION);
				listener.onSearchResult( 
							SearchThread.this, 
							identifyResult, 
							searchDuration );
				break;

			case MessageCodeError:
				Object errorCode = data.get( PROGRESS_ERROR_CODE );
				listener.onSearchError( 
							SearchThread.this, 
							data.getString( PROGRESS_MESSAGE ),
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
