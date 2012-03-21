/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.nfc.hiro99ma;

import com.android.nfc.DeviceHost.TagEndpoint;

import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;
import android.nfc.tech.TagTechnology;
import android.os.Bundle;
import android.util.Log;

import com.android.nfc.hiro99ma.NfcPcd;


/**
 * Native interface to the NFC tag functions
 */
public class NativeNfcTag implements TagEndpoint {
	static final boolean DBG = false;

	static final int STATUS_CODE_TARGET_LOST = 146;

	private int[] mTechList = { TagTechnology.NFC_F };
	private int[] mTechHandles = { -1 };
//	private int[] mTechLibNfcTypes = {5, 6};
	private Bundle[] mTechExtras;
	private byte[][] mTechPollBytes = new byte[1][];
	private byte[][] mTechActBytes;
	private byte[] mUid;

	// mConnectedHandle stores the *real* libnfc handle
	// that we're connected to.
	private int mConnectedHandle;

	// mConnectedTechIndex stores to which technology
	// the upper layer stack is connected. Note that
	// we may be connected to a libnfchandle without being
	// connected to a technology - technology changes
	// may occur runtime, whereas the underlying handle
	// could stay present. Usually all technologies are on the
	// same handle, with the exception of multi-protocol
	// tags.
	private int mConnectedTechIndex; // Index in mTechHandles

	private final String TAG = "NativeNfcTag";

	private boolean mIsPresent; // Whether the tag is known to be still present

	private NfcPcd.NfcId	mNfcId;


	@Override
	public synchronized boolean connect(int technology) {
		mNfcId = NfcPcd.getNfcId();
		boolean ret = false;
		
		Log.d(TAG, "connect:" + mNfcId.Type);
		switch(technology) {
		case TagTechnology.NFC_F:
			if(mNfcId.Type == NfcPcd.NfcIdType.NFCID2) {
				ret = reconnect();
			}
			break;
		}
		return ret;
	}

	@Override
	public synchronized void startPresenceChecking() {
		// Once we start presence checking, we allow the upper layers
		// to know the tag is in the field.
		Log.d(TAG, "startPresenceChecking");
		mIsPresent = true;
		if (mWatchdog == null) {
			mWatchdog = new PresenceCheckWatchdog();
			mWatchdog.start();
		}
	}

	@Override
	public synchronized boolean isPresent() {
		// Returns whether the tag is still in the field to the best
		// of our knowledge.
		return mIsPresent;
	}

	@Override
	public synchronized boolean disconnect() {
		Log.d(TAG, "disconnect");
		boolean result = false;

		mIsPresent = false;
		if (mWatchdog != null) {
			// Watchdog has already disconnected or will do it
			mWatchdog.end();
			try {
				mWatchdog.join();
			} catch (InterruptedException e) {
				// Should never happen.
			}
			mWatchdog = null;
			result = true;
		} else {
			result = true;
		}

		mConnectedTechIndex = -1;
		mConnectedHandle = -1;
		mTechExtras = null;
		return result;
	}

	@Override
	public synchronized boolean reconnect() {
		Log.d(TAG, "reconnect");
		boolean ret = false;
		
		mNfcId = NfcPcd.getNfcId();
		if(mNfcId.Type != NfcPcd.NfcIdType.NONE) {
			if(mUid != null) {
				if(NfcPcd.MemCmp(mUid, mNfcId.Id, mNfcId.Length, 0, 0) == true) {
					// UIDが同じだから、そのまま
					Log.d(TAG, "reconnect : same");
					return true;
				}
			}
			mUid = new byte[mNfcId.Length];
			//NfcPcd.MemCpy(mUid, mNfcId.Id, mNfcId.Length, 0, 0);
			System.arraycopy(mNfcId.Id, 0, mUid, 0, mNfcId.Length);
			mConnectedTechIndex = 0;	//1つだけ
			mConnectedHandle = 0;
			mTechHandles[0] = 0;
			mTechPollBytes[0] = new byte[10];
			System.arraycopy(mNfcId.Manufacture, NfcPcd.NfcId.POS_PMM, mTechPollBytes[0], 0, 8);
			mTechPollBytes[0][8] = mNfcId.Manufacture[NfcPcd.NfcId.POS_SC0];
			mTechPollBytes[0][9] = mNfcId.Manufacture[NfcPcd.NfcId.POS_SC1];
			mTechExtras = getTechExtras();
			ret = true;
			Log.d(TAG, "reconnect : new");
		}

		return ret;
	}

	@Override
	public synchronized byte[] transceive(byte[] data, boolean raw, int[] returnCode) {
		Log.d(TAG, "transceive");
		if (mWatchdog != null) {
			mWatchdog.pause();
		}
		byte[] result = new byte[NfcPcd.SIZE_RESBUF];
		int[] len = new int[1];
		boolean ret = NfcPcd.communicateThruEx((short)12000, data, data.length, result, len);
		byte[] result_new = null;
		if(ret) {
			result_new = new byte[len[0]];
			System.arraycopy(result, 0, result_new, 0, len[0]);
		}
		if (mWatchdog != null) {
			mWatchdog.doResume();
		}
		return result_new;
	}

	@Override
	public synchronized boolean checkNdef(int[] ndefinfo) {
		Log.d(TAG, "checkNdef");
		return false;
	}

	@Override
	public synchronized byte[] readNdef() {
		Log.d(TAG, "readNdef");
		if (mWatchdog != null) {
			mWatchdog.pause();
		}
		byte[] result = null;
		if (mWatchdog != null) {
			mWatchdog.doResume();
		}
		return result;
	}

	@Override
	public synchronized boolean writeNdef(byte[] buf) {
		Log.d(TAG, "writeNdef");
		if (mWatchdog != null) {
			mWatchdog.pause();
		}
		boolean result = false;
		if (mWatchdog != null) {
			mWatchdog.doResume();
		}
		return result;
	}

	@Override
	public synchronized boolean presenceCheck() {
		Log.d(TAG, "presenceCheck");
		if (mWatchdog != null) {
			mWatchdog.pause();
		}
		boolean result;
		if(NfcPcd.getNfcId().Type != NfcPcd.NfcIdType.NONE) {
			result = true;
		} else {
			result = false;
		}
		if (mWatchdog != null) {
			mWatchdog.doResume();
		}
		return result;
	}

	@Override
	public synchronized boolean formatNdef(byte[] key) {
		Log.d(TAG, "formatNdef");
		if (mWatchdog != null) {
			mWatchdog.pause();
		}
		boolean result = false;
		if (mWatchdog != null) {
			mWatchdog.doResume();
		}
		return result;
	}

	@Override
	public synchronized boolean makeReadOnly() {
		Log.d(TAG, "makeReadOnly");
		if (mWatchdog != null) {
			mWatchdog.pause();
		}
		boolean result = false;
		if (mWatchdog != null) {
			mWatchdog.doResume();
		}
		return result;
	}

	@Override
	public synchronized boolean isNdefFormatable() {
		Log.d(TAG, "isNdefFormatable");
		return false;
	}

	@Override
	public int getHandle() {
		// This is just a handle for the clients; it can simply use the first
		// technology handle we have.
		if (mTechHandles.length > 0) {
			return mTechHandles[0];
		} else {
			return 0;
		}
	}

	@Override
	public byte[] getUid() {
		Log.d(TAG, "getUid : " + mUid.length);
		return mUid;
	}

	@Override
	public int[] getTechList() {
		Log.d(TAG, "getTechList");
		return mTechList;
	}

	@Override
	public int getConnectedTechnology() {
		Log.d(TAG, "getConnectedTechnology : mConnectedTechIndex=" + mConnectedTechIndex);
		if (mConnectedTechIndex != -1 && mConnectedTechIndex < mTechList.length) {
			return mTechList[mConnectedTechIndex];
		} else {
			return 0;
		}
	}

	@Override
	public void removeTechnology(int tech) {
		Log.d(TAG, "removeTechnology");
//		synchronized (this) {
//			int techIndex = getTechIndex(tech);
//			if (techIndex != -1) {
//				int[] mNewTechList = new int[mTechList.length - 1];
//				System.arraycopy(mTechList, 0, mNewTechList, 0, techIndex);
//				System.arraycopy(mTechList, techIndex + 1, mNewTechList, techIndex,
//						mTechList.length - techIndex - 1);
//				mTechList = mNewTechList;
//
//				int[] mNewHandleList = new int[mTechHandles.length - 1];
//				System.arraycopy(mTechHandles, 0, mNewHandleList, 0, techIndex);
//				System.arraycopy(mTechHandles, techIndex + 1, mNewTechList, techIndex,
//						mTechHandles.length - techIndex - 1);
//				mTechHandles = mNewHandleList;
//
//				int[] mNewTypeList = new int[mTechLibNfcTypes.length - 1];
//				System.arraycopy(mTechLibNfcTypes, 0, mNewTypeList, 0, techIndex);
//				System.arraycopy(mTechLibNfcTypes, techIndex + 1, mNewTypeList, techIndex,
//						mTechLibNfcTypes.length - techIndex - 1);
//				mTechLibNfcTypes = mNewTypeList;
//			}
//		}
	}


	@Override
	public Bundle[] getTechExtras() {
		Log.d(TAG, "getTechExtras");
		synchronized (this) {
			//if (mTechExtras != null) return mTechExtras;
			mTechExtras = new Bundle[mTechList.length];
			for (int i = 0; i < mTechList.length; i++) {
				Bundle extras = new Bundle();
				switch (mTechList[i]) {
					case TagTechnology.NFC_A: {
						byte[] actBytes = mTechActBytes[i];
						if ((actBytes != null) && (actBytes.length > 0)) {
							extras.putShort(NfcA.EXTRA_SAK, (short) (actBytes[0] & (short) 0xFF));
						} else {
							// Unfortunately Jewel doesn't have act bytes,
							// ignore this case.
						}
						extras.putByteArray(NfcA.EXTRA_ATQA, mTechPollBytes[i]);
						break;
					}

					case TagTechnology.NFC_B: {
						// What's returned from the PN544 is actually:
						// 4 bytes app data
						// 3 bytes prot info
						byte[] appData = new byte[4];
						byte[] protInfo = new byte[3];
						if (mTechPollBytes[i].length >= 7) {
							System.arraycopy(mTechPollBytes[i], 0, appData, 0, 4);
							System.arraycopy(mTechPollBytes[i], 4, protInfo, 0, 3);

							extras.putByteArray(NfcB.EXTRA_APPDATA, appData);
							extras.putByteArray(NfcB.EXTRA_PROTINFO, protInfo);
						}
						break;
					}

					case TagTechnology.NFC_F: {
						byte[] pmm = new byte[8];
						byte[] sc = new byte[2];
						if (mTechPollBytes[i].length >= 8) {
							// At least pmm is present
							System.arraycopy(mTechPollBytes[i], 0, pmm, 0, 8);
							extras.putByteArray(NfcF.EXTRA_PMM, pmm);
						}
						if (mTechPollBytes[i].length == 10) {
							System.arraycopy(mTechPollBytes[i], 8, sc, 0, 2);
							extras.putByteArray(NfcF.EXTRA_SC, sc);
						}
						break;
					}

//					case TagTechnology.ISO_DEP: {
//						if (hasTech(TagTechnology.NFC_A)) {
//							extras.putByteArray(IsoDep.EXTRA_HIST_BYTES, mTechActBytes[i]);
//						}
//						else {
//							extras.putByteArray(IsoDep.EXTRA_HI_LAYER_RESP, mTechActBytes[i]);
//						}
//						break;
//					}

//					case TagTechnology.NFC_V: {
//						// First byte response flags, second byte DSFID
//						if (mTechPollBytes[i] != null && mTechPollBytes[i].length >= 2) {
//							extras.putByte(NfcV.EXTRA_RESP_FLAGS, mTechPollBytes[i][0]);
//							extras.putByte(NfcV.EXTRA_DSFID, mTechPollBytes[i][1]);
//						}
//						break;
//					}

//					case TagTechnology.MIFARE_ULTRALIGHT: {
//						boolean isUlc = isUltralightC();
//						extras.putBoolean(MifareUltralight.EXTRA_IS_UL_C, isUlc);
//						break;
//					}

					default: {
						// Leave the entry in the array null
						continue;
					}
				}
				mTechExtras[i] = extras;
			}
			return mTechExtras;
		}
	}

	@Override
	public NdefMessage[] findAndReadNdef() {
		Log.d(TAG, "findAndReadNdef");
		return null;
	}



	///////////////////////////////////////////////////
	// WDT
	///////////////////////////////////////////////////

	private PresenceCheckWatchdog mWatchdog;
	class PresenceCheckWatchdog extends Thread {

		private int watchdogTimeout = 125;

		private boolean isPresent = true;
		private boolean isStopped = false;
		private boolean isPaused = false;
		private boolean doCheck = true;

		public synchronized void pause() {
			isPaused = true;
			doCheck = false;
			this.notifyAll();
		}

		public synchronized void doResume() {
			isPaused = false;
			// We don't want to resume presence checking immediately,
			// but go through at least one more wait period.
			doCheck = false;
			this.notifyAll();
		}

		public synchronized void end() {
			isStopped = true;
			doCheck = false;
			this.notifyAll();
		}

		public synchronized void setTimeout(int timeout) {
			watchdogTimeout = timeout;
			doCheck = false; // Do it only after we have waited "timeout" ms again
			this.notifyAll();
		}

		@Override
		public synchronized void run() {
			if (DBG) Log.d(TAG, "Starting background presence check");
			while (isPresent && !isStopped) {
				try {
					if (!isPaused) {
						doCheck = true;
					}
					this.wait(watchdogTimeout);
					if (doCheck) {
						isPresent = isPresent();
					} else {
						// 1) We are paused, waiting for unpause
						// 2) We just unpaused, do pres check in next iteration
						//       (after watchdogTimeout ms sleep)
						// 3) We just set the timeout, wait for this timeout
						//       to expire once first.
						// 4) We just stopped, exit loop anyway
					}
				} catch (InterruptedException e) {
					// Activity detected, loop
				}
			}
			mIsPresent = false;
			// Restart the polling loop

			Log.d(TAG, "Tag lost, restarting polling loop");
			disconnect();
			if (DBG) Log.d(TAG, "Stopping background presence check");
		}
	}
}
