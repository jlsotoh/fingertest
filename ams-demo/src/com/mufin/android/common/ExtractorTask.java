package com.mufin.android.common;

import java.io.Serializable;
import java.util.EventListener;

import com.mufin.ears.xtr.Fingerprint;

public interface ExtractorTask {

	/** codes for working queue */
	public enum ParamCode implements Serializable {
		/** push samples to prepared query */
		add_samples, 
		/** pushing samples finished and start identification */
		cancel;
	}

	/** the message listener for ExtractorThread */
	public interface Listener extends EventListener {
		/**
		 * notify if fingerprint is ready to identify
		 * @param source the origin of the event
		 * @param fp the fingerprint
		 * @param fingerprintPosition the fingerprint position in the audio
		 */
		public void onFingerprintFull( ExtractorTask source, Fingerprint fp, long fingerprintPosition );
		/**
		 * message if error during process occured
		 * @param source the origin of the event
		 * @param errorMessage the message string
		 * @param errorCode optional error code
		 */
		public void onExtractorError( ExtractorTask source, String errorMessage, Integer errorCode );
	}
	
	/**
	 * execute the task
	 * @param listener
	 */
	public void start( Listener listener );
	
	/**
	 * push samples to prepared query
	 * @param samples
	 */
	public void putSamples( short[] samples )
	throws IllegalStateException;
	
	/**
	 * cancel the task<br/>
	 * finishes the current extraction and exit the task.<br/>
	 * remaining samples in queue will be discarded.
	 */
	public void cancel();
	
	/**
	 * checks the task status and returns true if the task is running and awaits events
	 * @return true if the thread still wait for samples to extract, false otherwise
	 */
	public boolean isRunning();
	
	/**
	 * get the EARS extractor version from library interface
	 * @return the EARS extractor version string
	 */
	public String getXtrVersion();
}
