package com.mufin.android.common;



public class CircularShortBuffer {
	/** the buffer to write to */
	private final short[] buffer;
	/** the size of the buffer */
	private final int size;
	/** the current write position */
	private int curPos;
	
	public CircularShortBuffer(int size) {
		super();
		this.size = size;
		this.buffer = new short[size];
		curPos = 0;
	}
	
	/**
	 * put values to the buffer and set new position of the buffer head<br/>
	 * same as put(values, 0, values.length);
	 * @param values
	 */
	public void put(short[] values) {
		put(values, 0, values.length);
	}
	/**
	 * put values to the buffer and set new position of the buffer head
	 * @param values the values to append to buffer
	 * @param offset the start index in values to put
	 * @param length the length to put
	 */
	public void put(short[] values, int offset, int length) {
		// invalid buffer
		if(values == null) throw new IllegalArgumentException("values must not be null");
		// valid buffer but no content, no need to process
		if(values.length == 0) return;
		
		// pos is at end of buffer, reset
		if(curPos == size) {
			curPos = 0;
		}
		
		// want to put more into buffer than buffer.size, reset the values to fit buffer
		if(length > size) {
			offset = length - size + offset;
			length = size;
		}
		
		final int head = curPos + length;
		if(head <= size) { // values have enough space in buffer, write completely
			System.arraycopy(values, offset, buffer, curPos, length);
			curPos = head;
		} else { // wrap values, write first part at the end and second part at start
			int tail = head % size;
			int front = length - tail;
			System.arraycopy(values, offset, buffer, curPos, front);
			System.arraycopy(values, offset + front, buffer, 0, tail);
			curPos = tail;
		}
	}

	/**
	 * @return the buffer size
	 */
	public int getSize() {
		return size;
	}
	/**
	 * @return the current head position in buffer
	 */
	public int getCurPos() {
		return curPos;
	}

	/**
	 * unfold the circular buffer to an array with {@link #getCurPos()} as last element
	 * @return the linear array from circular buffer 
	 */
	public short[] getBuffer(int offset, int length) {
		// determine the start position in ringbuffer
		int headPos = curPos + offset;
		
		short[] linear = new short[length];
		
		// set offset value to correct range
		headPos %= size;
		
		// interate over ring, if length > size
		int fromPos = headPos;
		int toPos = 0;
		int l = Math.min(length, size);
		while(length > 0) {
			copyIntoBuffer(linear, fromPos, l, toPos);
			toPos += l;
			length -= l;
			l = Math.min(length, size);
		}
		return linear;
	}
	
	/**
	 * helper method to copy from rung buffer into linear buffer
	 * @param buf
	 * @param fromPos
	 * @param length
	 */
	private void copyIntoBuffer(short[] buf, int fromPos, int length, int toPos) {
		
		final int headLen = size - fromPos;
		
		if(length < headLen) {
			// copy the head, the head provides enough data
			System.arraycopy(buffer, fromPos, buf, toPos, length);
		} else {
			final int tailLen = length - headLen;
			// copy the head
			System.arraycopy(buffer, fromPos, buf, toPos, headLen);
			// copy the tail
			System.arraycopy(buffer, 0, buf, toPos + headLen, tailLen);
		}
	}
	
	public short[] getRawBuffer() {
		return buffer;
	}

	public short getBufferValue(int index) {
		return buffer[index];
	}
}
