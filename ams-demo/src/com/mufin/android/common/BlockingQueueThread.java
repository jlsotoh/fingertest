package com.mufin.android.common;

import java.io.Serializable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

public abstract class BlockingQueueThread extends Thread {

	/** the message queue */
	private BlockingQueue<Bundle> queue = null; // TODO try volatile
	
	/** the handler class to send listener messages */
	private Handler observer = null;
	
	/** flag to cancel thread loop */
	private volatile boolean cancelled = false;

	protected BlockingQueueThread() {
		super();
		init();
	}

	protected BlockingQueueThread(String threadName) {
		super(threadName);
		init();
	}
	private void init()
	{
		queue = new LinkedBlockingQueue<Bundle>();
	}
	/**
	 * execute the task
	 * @param listener
	 */
	public void start(Handler observer) {
		this.observer = observer;
		
		this.start();
	}

	/**
	 * send message to observer
	 * @param what Value to assign to the what member. (see: {@link Message#obtain(Handler, int)})
	 * @param data the message content
	 */
	protected void sendMessage( int what, Bundle data )
	{
		if(observer != null)
		{
			Message message = Message.obtain( observer, what );
			if( data != null )
				message.setData( data );
			observer.sendMessage( message );
		}
	}

	protected BlockingQueue<Bundle> getQueue() {
		return queue;
	}

	/**
	 * checks the task status and returns true if the task is running and awaits events
	 * @return
	 */
	public boolean isRunning() {
		return (!isCancelled() && isAlive());
	}
	/**
	 * @return the cancelled
	 */
	public boolean isCancelled() {
		return cancelled;
	}
	/**
	 * @param cancelled the cancelled to set
	 */
	public void setCancelled(boolean cancelled) {
		this.cancelled = cancelled;
	}
	
	protected static class BundleObject<S> implements Serializable
	{
		private static final long serialVersionUID = -5386512758430150886L;
		
		// S class is not serializable, so wrap around Fingerprint
		protected S obj;
		
		protected BundleObject(S obj) {
			this.obj = obj;
		}
	}
}
