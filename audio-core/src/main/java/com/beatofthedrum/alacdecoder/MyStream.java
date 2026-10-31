/*
** MyStream.java
**
** Copyright (c) 2011 Peter McQuillan
**
** All Rights Reserved.
**                       
** Distributed under the BSD Software License (see license.txt)  
**
*/

package com.beatofthedrum.alacdecoder;

class MyStream
{
	public java.io.DataInputStream stream;
	public int currentPos = 0;
    public int pendingByte = -2; // -2 means no lookahead; -1 means EOF.
    public byte[] read_buf = new byte[8];
}