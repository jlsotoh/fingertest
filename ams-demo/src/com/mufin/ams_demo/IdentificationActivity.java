/*
 * Copyright (C) mufin GmbH. All rights reserved.
 */
package com.mufin.ams_demo;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Map;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import com.mufin.ams_content.MetadataServiceClient;
import com.mufin.ams_content.ResultMetadata;
import com.mufin.ams_demo.components.CurrentResult;
import com.mufin.ams_demo.components.RecordingIdentificationController;
import com.mufin.ams_demo.components.RecordingIdentificationController.EarsType;
import com.mufin.ams_demo.components.RecordingIdentificationController.RecordingMode;
import com.mufin.ears.common.IdentifyResult;
import com.mufin.ears.common.License;
import com.mufin.ears.common.LicenseException;

public class IdentificationActivity extends Activity {

	private RecordingIdentificationController controller;
	
	/** the metadata webservice client class */
	private MetadataServiceClient client;
	/** result display helper class */
	private CurrentResult currentResult;
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
		
		controller = new RecordingIdentificationController(
						getRecordingMode(), 
						getQueryDuration(), 
						0, 
						getRecordingDuration(),
						recordingOverlapEnabled(),
						buildControllerSettings());
		
		controller.setControllerEventsHandler(new ControllerEvents());
		
        setContentView(R.layout.identification);
        
        setRecording(false); // init some things
       
        // init helper class
        currentResult = new CurrentResult();

        // init webservice client if url is provided
        client = null;
        if (!Settings.METADATA_URL.equals(""))
        {
	        client = new MetadataServiceClient();
	        client.setBaseURL(Settings.METADATA_URL);
        }

        // hide result screen on start
        View r = findViewById(R.id.current_result);
        r.setVisibility(View.INVISIBLE);
        
        // hide progress bar on start
        View p = findViewById(R.id.progress_bar);
        p.setVisibility(View.INVISIBLE);

        try
		{
			InputStream is = null;
			
	        try
	        {
		        is = getAssets().open( Settings.LICENSE_FILE );
	        }
	        catch( IOException e )
	        {
	        	throw new LicenseException( "license file not found" );
	        }
	       
			String license = License.getLicenseString( is );
			
			License.register( this, license, Settings.USER_ID );
			
			for( Map.Entry< Integer, String > entry : Settings.registrationInfo.entrySet() )
	        {
				License.registerComponent( entry.getKey().intValue(), entry.getValue() );
	        }
		}
		catch( LicenseException e) 
		{
			buildOkAlert( "License error:\n" + e.getMessage() ).show();
		}
    }
    
    @Override
	protected void onDestroy() {
		controller.destroy();

		super.onDestroy();
	}
    
    /**
     * identification.xml start_button clicked
     * @param view
     */
	public void startButtonClicked(View view) {
		if(controller.isRecording()) {
			stopRecording();
		} else {
			startRecording();
		}
	}
	
	protected RecordingMode getRecordingMode() {
		return (Settings.CONTINUOUS_IDENTIFY ? 
				RecordingMode.continuous : 
				RecordingMode.single_scan);
	}

	protected int getQueryDuration() {
		return Settings.QUERY_DURATION;
	}

	protected int getRecordingDuration() {
		return Settings.RECORDING_DURATION;
	}
	
	protected boolean recordingOverlapEnabled() {
		return Settings.RECORDING_OVERLAP;
	}

	protected RecordingIdentificationController.EarsSettings buildControllerSettings()
	{
		return new RecordingIdentificationController.EarsSettings(
						Settings.HOST, 
						Settings.PORT,
						Settings.PATH,
						Settings.USE_REDIRECTOR,
						Settings.NETWORK_TIMEOUT);
	}

	/**
	 * init and run sample recorder thread
	 */
	private void startRecording()
	{
		Log.d( getClass().getName(), "startRecording() ");
		
		// re-set all controller data, if sth. changed (not in this demo app, but if settings dialog exists, do it here)
		controller.setRecordingMode( getRecordingMode() );
		controller.setQueryDuration( getQueryDuration() );
		controller.setRecordingDuration( getRecordingDuration() );
		controller.setSettings( buildControllerSettings() );
		
		setRecording( true );

		controller.startRecorder();
	}
	
	/**
	 * stop sample recorder, identification service and reset layout
	 */
	private void stopRecording()
	{
		Log.d( getClass().getName(), "stopRecording() ");
		
		controller.stopRecorder();
		
		setRecording( false );
		
		// update screen
		currentResult.hideIfNoResult(this);
	}

	/**
	 * set recording and button state
	 */
	public void setRecording(boolean recording) {
		Log.d( getClass().getName(), "setRecording("+recording+") " );

		// update button state
		Button button = (Button)findViewById( R.id.start_button);
		if(recording) {
			button.setText(R.string.button_stop);
		} else {
			button.setText(R.string.button_start);
		}
        button.requestLayout();
        
        View p = findViewById(R.id.progress_bar);
        if(recording) {
        	p.setVisibility(View.VISIBLE);
		} else {
			p.setVisibility(View.INVISIBLE);
		}
	}
	
	/**
	 * helper method to show alert dialog
	 * @param textId
	 * @return
	 */
	private AlertDialog buildOkAlert(String text) {
		AlertDialog.Builder builder = new AlertDialog.Builder( this );
		builder.setMessage( text )
				.setCancelable( true )
				.setIcon( android.R.drawable.ic_dialog_alert )
				.setPositiveButton( R.string.ok, new DialogInterface.OnClickListener()
				{
					public void onClick( DialogInterface dialog, int id )
					{
						dialog.cancel();
					}
				} );
		
		return builder.create();
	}
	
	private class ControllerEvents
	extends RecordingIdentificationController.ControllerEvents
	{
		@Override
		protected void recorderInited(int sampleRate, int channels)
		{
			super.recorderInited(sampleRate, channels);
		}

		@Override
		protected void recorderUpdate(short[] samples, int samplesRecordedCount)
		{
			// nothing to do in demo app, but this method can be used to update gui, while recording
			super.recorderUpdate(samples, samplesRecordedCount);
		}

		@Override
		protected void recorderRecorded(short[] samples, boolean recorderFinished)
		{
			if(!controller.isRecording()) return; // ignore last result, if recording was stopped before

			Log.d( getClass().getName(), "recorderRecorded samples: " + samples.length );

			super.recorderRecorded(samples, recorderFinished);

			if(recorderFinished)
			{
				// if it was the last iteration of sample recorder, set identify status for actitivty
				// to show result screen after recording and no retry following
				stopRecording();
			}
		}

		@Override
		protected void recorderInitError()
		{
			Log.d( getClass().getName(), "recorderInitError" );
			
			String alertText = IdentificationActivity.this.getText(R.string.audio_recording_error_text).toString();
			// show error message
			buildOkAlert(alertText)
					.show();

			stopRecording();

			super.recorderInitError();
		}

		@Override
		protected void earsError( final EarsType earsType, final String errorMessage, final Integer errorCode )
		{
			Log.d( getClass().getName(), "earsError: " +
					(errorCode == null ? "" : "(" + errorCode + ")") + errorMessage );

			String alertText = IdentificationActivity.this.getText(R.string.rears_error_text).toString();
			alertText += "\n" + errorMessage + (errorCode == null ? "" : " (" + errorCode + ")");
			
			// show error message
			buildOkAlert(alertText)
					.show();

			stopRecording();
		}

		@Override
		protected void earsResult( final EarsType earsType, final ArrayList<IdentifyResult> identifyResult, final long searchDuration)
		{
			Log.d( getClass().getName(), "earsResult result: " + identifyResult + " searchDuration: " + searchDuration);
			
			if(!controller.isRecording()) return; // ignore last result, if recording was stopped before
			
			if(earsType != EarsType.ams) return; // ignore result, if it is unregistered type (should never happen in AMS only app)
			
			Log.d( getClass().getName(), "start metadata thread" );
			
			// handler must be created in current looper thread, to avoid creating looper within
			final Handler handler = new Handler();
			
			// background thread to request metadata to avoid activity stuck
			new Thread(new Runnable() {
				
				@Override
				public void run() {
					Log.d( getClass().getName(), "metadata thread run" );
					
					IdentifyResult result = null;
					ResultMetadata resultMetadata = null;
					try {
						if(identifyResult!=null && identifyResult.size()>0 && identifyResult.get(0).getConfidence() >= Settings.CONFIDENCE_THRESHOLD) {
							
							result = identifyResult.get(0);
							
							Log.d( getClass().getName(), " result id: "+result.getId()+" " + " timestamp: " + result.getTimestamp() );

							// try to get metadata from webservice
							if (client != null)
							{
								resultMetadata = client.getMetadata(IdentificationActivity.this, result.getId());
							}
						}
					} catch (Exception e) {
						Log.e( getClass().getName(), e.getMessage(), e );
					}
					Log.d( getClass().getName(), "metadata " + result );

					if(result == null) {
						currentResult.setResult(IdentificationActivity.this, null, null, CurrentResult.ResultCause.searching);
					} else {
						// set as current result
						currentResult.setResult(IdentificationActivity.this, result, resultMetadata, CurrentResult.ResultCause.identify);
						
						// stop if result in single mode
						if(!Settings.CONTINUOUS_IDENTIFY) {
							handler.post(new Runnable() {
								@Override
								public void run() {
									stopRecording();
								}
							});
						}
					}
				}
			}).start();
			
			Log.d( getClass().getName(), "metadata thread started" );
		}
	}
}
