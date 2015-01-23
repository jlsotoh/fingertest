/*
 * Copyright (C) mufin GmbH. All rights reserved.
 */
package com.mufin.android.common;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;

import com.mufin.ears.common.IdentifyResult;
import com.mufin.ears.common.LicenseException;
import com.mufin.ears.xtr.Fingerprint;

/**
 * interface for asynchronous EARS interface handling.<br/>
 * 
 * Audio recognition task.<br>
 * Approach: 
 * <li>Start task: {@link #start(Listener)}, parameter: {@link Listener} listener</li>
 * <li>multiple {@link #putFingerprint(Fingerprint, long)}</li>
 * <li>Event is triggered in listener when result is
 * available or an error occurred.</li>
 */
public interface SearchTask
{
	
	/** codes for current status of SearchTask */
	public enum StatusCode {
		/** pre-init state */
		notInitialized, 
		/** error state */
		error,
		/** identification running */
		identifying,
		/** identification running */
		free
	};

	/** codes for working queue */
	public enum ParamCode implements Serializable {
		/** pushing samples finished and start identification */
		identify, 
		/** indicate queue clear and task end, thread cannot be used until recreation */
		cancel
	}

	/** the message listener for SearchTask */
	public interface Listener extends EventListener {
		/**
		 * message if error occured during process
		 * @param source the origin of the event
		 * @param errorMessage the message string
		 * @param errorCode optional error code
		 */
		public void onSearchError( SearchTask source, String errorMessage, Integer errorCode );
		/**
		 * message if result returned from identification process
		 * @param source the origin of the event
		 * @param identifyResult the result list, or null if no result
		 * @param searchDuration the duration of the server request
		 */
		public void onSearchResult( SearchTask source, ArrayList<IdentifyResult> identifyResult, long searchDuration );
	}
	
	/**
	 * interface abstraction for the search method<br/>
	 * used to feed the SearchTask with diffent search methods
	 */
	public interface SearchWorker
	{
		/**
		 * implement the user-defined search method
		 * @param query the fingerprint to search
		 * @param queryPosition the start time of the query
		 * @param numResults the desired number of results
		 * @param result the result list to write the results to
		 * @return see the return value of the implemented search method
		 * @throws LicenseException 
		 */
		public int search( Fingerprint query, long queryPosition, int numResults, List<IdentifyResult> result ) throws LicenseException;
		/**
		 * is called by SearchTask, if the worker is no longer needed 
		 * to perform cleanup if necessary
		 */
		public void destroy();
	}
	
	/**
	 * interface for lazy building SearchWorker instance
	 */
	public interface SearchWorkerBuilder
	{
		/**
		 * contructs the SearchWorker instance
		 * @return the SearchWorker
		 * @throws LicenseException 
		 */
		public SearchWorker build() throws LicenseException;
	}

	/**
	 * execute the task
	 * @param worker for the search method, the resource-ownership move to SearchTask
	 * @param listener the event listener for ears events
	 */
	public void start( SearchWorker worker, Listener listener );
	
	/**
	 * add a fingerprint to identification queue
	 * @param fp the fingerprint to search
	 * @param queryPosition the query position in the audio
	 */
	public void putFingerprint( Fingerprint fp, long queryPosition )
	throws IllegalStateException;
	
	/**
	 * cancel the task<br/>
	 * finishes the current search and exit the task.<br/>
	 * remaining fingerprints in queue will be discarded.
	 */
	public void cancel();
	
	/**
	 * checks the task status and returns true if the task is running and awaits events
	 * @return true if the thread still wait for fingerprints to search, false otherwise
	 */
	public boolean isRunning();

	/**
	 * Retrieve current status.
	 * @return
	 */
	public StatusCode getEarsStatus();
}
