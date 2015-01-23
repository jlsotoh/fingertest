/*
 * Copyright (C) mufin GmbH. All rights reserved.
 */
package com.mufin.ams_demo.components;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.mufin.ams_content.ResultMetadata;
import com.mufin.ams_demo.R;
import com.mufin.ears.common.IdentifyResult;

/**
 * helper class to handle the display of the result view in different states 
 */
public class CurrentResult {

	private static final TimeZone timeZone = TimeZone.getTimeZone("UTC");
	private static final DateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
	
	static {
		// metadata webservice works with UTC time
		timeFormat.setTimeZone(timeZone);
	}

	private IdentifyResult result;
	private ResultMetadata resultMetadata;
	
	/**
	 * the way the result was determined<br/>
	 * <code>identify</code> is used if a valid result was returned from identify method, a valid result is also NULL if nothing found<br/>
	 * <code>nonetwork</code> is used if NULL result caused by connection error
	 */
	public enum ResultCause {
		/** identify successfull, maybe result or not (null) */
		identify, 
		/** searching status before identify */
		searching;
	}

	public CurrentResult() {
		super();
	}

	/**
	 * set the result and update view with this informations
	 * @param context the activity context
	 * @param result the result to show
	 * @param resultMetadata the result incl metadata
	 * @param cause the cause is needed to handle different views
	 */
	public void setResult(final Activity context, final IdentifyResult result, final ResultMetadata resultMetadata, final ResultCause cause) {
		// do not replace the whole content, if result is equal, just update time
		if(this.resultMetadata != null &&
				resultMetadata != null &&
				this.resultMetadata.equals(resultMetadata)) { // equals method ignores timestamp
			updateResult(context, result, resultMetadata);
		} else {
			showResult(context, result, resultMetadata, cause);
		}
		
		this.resultMetadata = resultMetadata;
		this.result = result;
	}
	
	/**
	 * leave valid match visible, but invisible for "searching..." screen
	 */
	public void hideIfNoResult(final Activity context) {
		Log.d( getClass().getName(), "hideIfNoResult() result: " + result);
		
		if(result == null) {
			context.runOnUiThread(new Runnable() {
				@Override
				public void run() {
					// container
					ViewGroup container = (ViewGroup)context.findViewById(R.id.current_result);
					container.setVisibility(View.INVISIBLE);
				}
			});
		}
	}
	
	/**
	 * just update time field, not whole view
	 * @param context
	 * @param resultMetadata
	 */
	private void updateResult(final Activity context, final IdentifyResult result, final ResultMetadata resultMetadata) {
		context.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				// container
				ViewGroup container = (ViewGroup)context.findViewById(R.id.current_result);
				container.setVisibility(View.VISIBLE);
				
				// the linear layout for textviews
				ViewGroup v = (ViewGroup)container.findViewById(R.id.metadata);

				// result time
				TextView time = (TextView)v.findViewById(R.id.current_tag_time);
				time.setText(timeFormat.format(result.getTimestamp()));
			}
		});
	}
	
	/**
	 * update view with informations from result
	 * @param context the activity context
	 * @param result the result to show
	 * @param resultMetadata the result incl metadata
	 * @param cause the is needed to handle different views
	 */
	private void showResult(final Activity context, final IdentifyResult result, final ResultMetadata resultMetadata, final ResultCause cause) {
		context.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				// container
				ViewGroup container = (ViewGroup)context.findViewById(R.id.current_result);
				container.setVisibility(View.VISIBLE);

				// the linear layout for textviews
				ViewGroup v = (ViewGroup)container.findViewById(R.id.metadata);

				// channel and artist name
				TextView title = (TextView)v.findViewById(R.id.current_title);
				// result time
				TextView time = (TextView)v.findViewById(R.id.current_tag_time);
				// result cover image
				ImageView coverImageView = (ImageView) container.findViewById( R.id.current_channel_cover );
				
				// prevent OutOfMemoryError by deleting the bitmap resource and add again later if needed
				// bitmap recycle is the recommended way to clean memory for drawables
				Drawable toRecycle = coverImageView.getDrawable();
				if (toRecycle != null) {
				    Bitmap b = ((BitmapDrawable)toRecycle).getBitmap();
				    if(b != null) b.recycle();
				}
				
				coverImageView.setImageURI(null);
				
				// check for type of result to show the right messages or the result
				switch (cause) {
				case searching:
					title.setText( context.getText( R.string.searching ) );
					time.setText("");

					coverImageView.setVisibility(View.INVISIBLE);
					break;
					
				case identify:
					
					String titleText = "";
					
					if(resultMetadata != null) {
						if(resultMetadata.getArtist() != null) {
							titleText += resultMetadata.getArtist();
							titleText += context.getString(R.string.colon) + " ";
						}
						
						if(resultMetadata.getTitle() != null) {
							titleText += resultMetadata.getTitle();
						} else {
							titleText += resultMetadata.getId();
						}
					} else {
						titleText += result.getId();
					}
						
					title.setText(titleText);
										
					time.setText(timeFormat.format(result.getTimestamp()));
					
					File cover = (resultMetadata!=null ? resultMetadata.getCover() : null);
					Bitmap bmCover;
					if( cover != null ) {
						bmCover = BitmapFactory.decodeFile(cover.getAbsolutePath());
						coverImageView.setImageBitmap(bmCover);
					}
					coverImageView.setVisibility(View.VISIBLE);
					
					break;
				}
			}
		});
	}
}
