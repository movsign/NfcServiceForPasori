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

import com.android.nfc.DeviceHost;
import com.android.nfc.LlcpException;
import com.android.nfc.NfcService;
import com.android.nfc.hiro99ma.NativeNfcTag;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.content.SharedPreferences;
import android.nfc.ErrorCodes;
import android.nfc.tech.Ndef;
import android.nfc.tech.TagTechnology;
import android.util.Log;
import android.os.Handler;
import android.os.Message;


//for NfcPcd
import com.android.nfc.hiro99ma.NfcPcd;
import com.android.nfc.hiro99ma.NfcPcd.RecvBroadcast;
import android.content.BroadcastReceiver;
//import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.hardware.usb.UsbManager;

/**
 * Native interface to the NFC Manager functions
 */
public class NativeNfcManager implements DeviceHost {
	private static final String TAG = "NativeNfcManager";

	private static final String PREF = "NxpDeviceHost";

	private static final long FIRMWARE_MODTIME_DEFAULT = -1;

	@SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
	public static final String INTERNAL_TARGET_DESELECTED_ACTION = "com.android.nfc.action.INTERNAL_TARGET_DESELECTED";

	/* Native structure */
	private int mNative;

	private final DeviceHostListener mListener;
	private final Context mContext;

	private boolean mPresence = false;

	private NativeNfcTag mTag = new NativeNfcTag();

	public NativeNfcManager(Context context, DeviceHostListener listener) {
		mListener = listener;
		mContext = context;
	}

	@Override
	public void checkFirmware() {
		;
	}

	@Override
	public boolean initialize() {
		boolean ret = false;

		//NfcPcd
		UsbManager mgr = (UsbManager)mContext.getSystemService(Context.USB_SERVICE);
		IntentFilter filter = NfcPcd.init(mContext, mgr);
		if(filter != null) {
			mContext.registerReceiver(mUsbReceiver, filter);
			ret = true;
		}
		return ret;
	}

	@Override
	public boolean deinitialize() {
		if(NfcPcd.opened()) {
			mContext.unregisterReceiver(mUsbReceiver);
			NfcPcd.destroy();
		}
		return true;
	}

	BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			Log.d(TAG, "onReceive : " + intent.getAction());
			NfcPcd.RecvBroadcast ret = RecvBroadcast.UNKNOWN;
			synchronized (this) {
				ret = NfcPcd.receiveBroadcast(context, intent);
				if((ret == RecvBroadcast.PERMIT) || (ret == RecvBroadcast.ATTACHED)) {
					//OK
				} else {
					//fail
					deinitialize();
				}
			}
		}
	};

	private final static int MSG_POLL = 1;
	private final static int INTERVAL = 1000;	//msec
	private final class PollHandler extends Handler {
		boolean mPolling = false;

		@Override
		public void dispatchMessage(Message msg) {
			if(NfcPcd.opened() == false) {
				return;
			}
			if((mPolling == true) && (msg.what == MSG_POLL)) {
				byte[] res = new byte[NfcPcd.GGS_LEN];
				boolean bGGS = NfcPcd.getGeneralStatus(res);
				if(mPresence) {
					//検出中
//					byte[] res = new byte[NfcPcd.GGS_LEN];
//					boolean b = NfcPcd.getGeneralStatus(res);
					if((bGGS && (res[NfcPcd.GGS_ERR] == 0)) || !bGGS) {
						//どっかいった
						Log.d(TAG, "card remove : stst[" + bGGS + "] / field:" + res[NfcPcd.GGS_ERR]);
						mPresence = false;
						NfcPcd.rfOff();
						mListener.onRemoteFieldDeactivated();
					} else {
						//まだカードは健在
					}
				}
				if(!mPresence) {
					//未検出
					boolean b = NfcPcd.pollingF();
					if(b) {
						//カード検出
						mPresence = true;
						NfcPcd.NfcId nfcid = (NfcPcd.NfcId)NfcPcd.getNfcId().clone();
						b = NfcPcd.pollingF(0x12fc);	//Type3
						if(!b) {
							//Type3じゃないなら、元に戻すか
							NfcPcd.getNfcId().copy(nfcid);
						}
						nfcid = null;
						mListener.onRemoteEndpointDiscovered(mTag);
					} else {
						NfcPcd.rfOff();
					}
				}
				mHandler.sendEmptyMessageDelayed(MSG_POLL, INTERVAL);
			} else {
				super.dispatchMessage(msg);
			}
		}

		public void start() {
			mPolling = true;
			mHandler.sendEmptyMessage(MSG_POLL);
		}

		public void stop() {
			mPolling = false;
		}
	}
	private final PollHandler mHandler = new PollHandler();

	@Override
	public void enableDiscovery() {
		mHandler.start();
	}

	@Override
	public void disableDiscovery() {
		mHandler.stop();
	}

	@Override
	public int[] doGetSecureElementList() { return null; }

	@Override
	public void doSelectSecureElement() {}

	@Override
	public void doDeselectSecureElement() {}

	@Override
	public int doGetLastError() { return 0; }

	@Override
	public LlcpSocket createLlcpSocket(int sap, int miu, int rw,
			int linearBufferLength) throws LlcpException {
		throw new LlcpException(ErrorCodes.ERROR_SOCKET_CREATION);
	}

	@Override
	public LlcpServerSocket createLlcpServerSocket(int nSap, String sn, int miu,
			int rw, int linearBufferLength) throws LlcpException {
		return null;
	}


	@Override
	public boolean doCheckLlcp() {
		return false;
	}

	@Override
	public boolean doActivateLlcp() {
		return false;
	}


	@Override
	public void resetTimeouts() {}

	public void doAbort() {}

	@Override
	public boolean setTimeout(int tech, int timeout) {
		return false;
	}

	@Override
	public int getTimeout(int tech) {
		return 0;
	}


	//とりあえず、Type3だけにしておくが、他のも考えないとね
	@Override
	public boolean canMakeReadOnly(int ndefType) {
		return (ndefType == Ndef.TYPE_3);
	}

	//とりあえず、PN544の設定をそのまま使うけど、置き換えないとね
	@Override
	public int getMaxTransceiveLength(int technology) {
		switch (technology) {
			case (TagTechnology.NFC_A):
			case (TagTechnology.MIFARE_CLASSIC):
			case (TagTechnology.MIFARE_ULTRALIGHT):
				return 253; // PN544 RF buffer = 255 bytes, subtract two for CRC
			case (TagTechnology.NFC_B):
				return 0; // PN544 does not support transceive of raw NfcB
			case (TagTechnology.NFC_V):
				return 253; // PN544 RF buffer = 255 bytes, subtract two for CRC
			case (TagTechnology.ISO_DEP):
				/* The maximum length of a normal IsoDep frame consists of:
				 * CLA, INS, P1, P2, LC, LE + 255 payload bytes = 261 bytes
				 * such a frame is supported. Extended length frames however
				 * are not supported.
				 */
				return 261; // Will be automatically split in two frames on the RF layer
			case (TagTechnology.NFC_F):
				return 252; // PN544 RF buffer = 255 bytes, subtract one for SoD, two for CRC
			default:
				return 0;
		}
	}

	@Override
	public String dump() {
		return "hello";
	}
}
