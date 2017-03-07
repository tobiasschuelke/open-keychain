package org.sufficientlysecure.keychain.ui;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.cryptolib.SecureDataSocket;
import com.cryptolib.SecureDataSocketException;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.operations.results.ExportResult;
import org.sufficientlysecure.keychain.provider.TemporaryFileProvider;
import org.sufficientlysecure.keychain.service.BackupKeyringParcel;
import org.sufficientlysecure.keychain.service.input.CryptoInputParcel;
import org.sufficientlysecure.keychain.ui.base.CryptoOperationFragment;
import org.sufficientlysecure.keychain.ui.util.QrCodeUtils;
import org.sufficientlysecure.keychain.util.FileHelper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PrivateKeyExportFragment extends CryptoOperationFragment<BackupKeyringParcel, ExportResult> {
    public static final String ARG_MASTER_KEY_IDS = "master_key_ids";
    public static final int PORT = 5891;

    private ImageView mQrCode;
    private TextView mSentenceText;
    private TextView mSentenceHeadlineText;
    private Button mNoButton;
    private Button mYesButton;

    private Activity mActivity;
    private SecureDataSocket mSecureDataSocket;
    private SetupServerWithClientCameraTask mSetupServerTask;
    private SetupServerNoClientCameraTask mSetupServerNoCamTask;
    private String mIpAddress;
    private String mConnectionDetails;
    private long mMasterKeyId;
    private Uri mCachedBackupUri;

    public static PrivateKeyExportFragment newInstance(long masterKeyId) {
        PrivateKeyExportFragment frag = new PrivateKeyExportFragment();

        Bundle args = new Bundle();
        args.putLong(ARG_MASTER_KEY_IDS, masterKeyId);
        frag.setArguments(args);

        return frag;
    }

    /**
     * from: http://stackoverflow.com/a/13007325
     * <p>
     * Get IP address from first non-localhost interface
     *
     * @param useIPv4 true=return ipv4, false=return ipv6
     * @return address or empty string
     */
    private static String getIPAddress(boolean useIPv4) {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        //boolean isIPv4 = InetAddressUtils.isIPv4Address(sAddr);
                        boolean isIPv4 = sAddr.indexOf(':') < 0;

                        if (useIPv4) {
                            if (isIPv4)
                                return sAddr;
                        } else {
                            if (!isIPv4) {
                                int delim = sAddr.indexOf('%'); // drop ip6 zone suffix
                                return delim < 0 ? sAddr.toUpperCase() : sAddr.substring(0, delim).toUpperCase();
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
        } // for now eat exceptions
        return "";
    }

    @Override
    public void onAttach(Context context) {
        mActivity = (Activity) context;
        mIpAddress = getIPAddress(true);
        super.onAttach(context);
    }

    @Override
    public void onResume() {
        System.out.println("**DEBUG: onResume()");
        mSetupServerTask = new SetupServerWithClientCameraTask();
        mSetupServerTask.execute();
        super.onResume();
    }

    @Override
    public void onPause() {
        System.out.println("**DEBUG: onPause()");
        if (mSetupServerTask != null) {
            mSetupServerTask.cancel(true);
        }
        if (mSetupServerNoCamTask != null) {
            mSetupServerNoCamTask.cancel(true);
        }
        if (mSecureDataSocket != null) {
            mSecureDataSocket.close();
        }
        super.onPause();
    }

    @Override
    public View onCreateView(LayoutInflater i, ViewGroup c, Bundle savedInstanceState) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.private_key_export_fragment, c, false);

        Bundle args = getArguments();
        mMasterKeyId = args.getLong(ARG_MASTER_KEY_IDS);

        final View qrLayout = view.findViewById(R.id.private_key_export_qr_layout);
        mQrCode = (ImageView) view.findViewById(R.id.private_key_export_qr_image);
        final Button doesntWorkButton = (Button) view.findViewById(R.id.private_key_export_button);

        final View infoLayout = view.findViewById(R.id.private_key_export_info_layout);
        TextView ipText = (TextView) view.findViewById(R.id.private_key_export_ip);
        TextView portText = (TextView) view.findViewById(R.id.private_key_export_port);
        mSentenceHeadlineText = (TextView) view.findViewById(R.id.private_key_export_sentence_headline);
        mSentenceText = (TextView) view.findViewById(R.id.private_key_export_sentence);
        mNoButton = (Button) view.findViewById(R.id.private_key_export_sentence_not_matched_button);
        mYesButton = (Button) view.findViewById(R.id.private_key_export_sentence_matched_button);

        mQrCode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                System.out.println("**DEBUG: QR KLICK");
                showQrCodeDialog();
            }
        });

        doesntWorkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mSetupServerTask != null) {
                    mSetupServerTask.cancel(true);
                }
                if (mSecureDataSocket != null) {
                    mSecureDataSocket.close();
                }

                qrLayout.setVisibility(View.GONE);
                doesntWorkButton.setVisibility(View.GONE);

                infoLayout.setVisibility(View.VISIBLE);
                mSetupServerNoCamTask = new SetupServerNoClientCameraTask();
                mSetupServerNoCamTask.execute();
            }
        });

        ipText.setText(mIpAddress);
        portText.setText(String.valueOf(PORT));

        mNoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    mSecureDataSocket.comparedPhrases(false);
                } catch (SecureDataSocketException e) {
                    e.printStackTrace();
                }
                mActivity.finish();
            }
        });

        mYesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    mSecureDataSocket.comparedPhrases(true);
                    System.out.println("**DEBUG: createExport 2");
                    createExport();
                } catch (SecureDataSocketException e) {
                    System.out.println("mSecureDataSocket.comparedPhrases(true);");
                    e.printStackTrace();
                }
            }
        });

        loadQrCode();

        return view;
    }

    private void showQrCodeDialog() {
        Intent qrCodeIntent = new Intent(mActivity, QrCodeViewActivity.class);

        // create the transition animation - the images in the layouts
        // of both activities are defined with android:transitionName="qr_code"
        Bundle opts = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ActivityOptions options = ActivityOptions
                    .makeSceneTransitionAnimation(mActivity, mQrCode, "qr_code");
            opts = options.toBundle();
        }

        qrCodeIntent.putExtra(QrCodeViewActivity.EXTRA_QR_CODE_CONTENT, mConnectionDetails);
        qrCodeIntent.putExtra(QrCodeViewActivity.EXTRA_TITLE_RES_ID, R.string.title_export_private_key);
        ActivityCompat.startActivity(mActivity, qrCodeIntent, opts);
    }

    /**
     * Load QR Code asynchronously and with a fade in animation
     */
    private void loadQrCode() {
        if (mQrCode == null || mConnectionDetails == null) {
            return;
        }

        AsyncTask<Void, Void, Bitmap> loadTask =
                new AsyncTask<Void, Void, Bitmap>() {
                    protected Bitmap doInBackground(Void... unused) {
                        // render with minimal size
                        return QrCodeUtils.getQRCodeBitmap(mConnectionDetails, 0);
                    }

                    protected void onPostExecute(Bitmap qrCode) {
                        // scale the image up to our actual size. we do this in code rather
                        // than let the ImageView do this because we don't require filtering.
                        Bitmap scaled = Bitmap.createScaledBitmap(qrCode,
                                mQrCode.getHeight(), mQrCode.getHeight(),
                                false);
                        mQrCode.setImageBitmap(scaled);

                        // simple fade-in animation
                        AlphaAnimation anim = new AlphaAnimation(0.0f, 1.0f);
                        anim.setDuration(200);
                        mQrCode.startAnimation(anim);
                    }
                };
        loadTask.execute();
    }

    private void createExport() {
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String filename = Constants.FILE_ENCRYPTED_BACKUP_PREFIX + date
                + Constants.FILE_EXTENSION_ENCRYPTED_BACKUP_SECRET;

        if (mCachedBackupUri == null) {
            mCachedBackupUri = TemporaryFileProvider.createFile(mActivity, filename,
                    Constants.MIME_TYPE_ENCRYPTED_ALTERNATE);

            cryptoOperation(new CryptoInputParcel());
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] exportData = FileHelper.readBytesFromUri(mActivity, mCachedBackupUri);
                    mSecureDataSocket.write(exportData);
                    mSecureDataSocket.close();
                    mActivity.finish();
                } catch (IOException | SecureDataSocketException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    @Nullable
    @Override
    public BackupKeyringParcel createOperationInput() {
        return new BackupKeyringParcel(new long[]{mMasterKeyId}, true, false, mCachedBackupUri);
    }

    @Override
    public void onCryptoOperationSuccess(ExportResult result) {
        System.out.println("**DEBUG: createExport 3");
        createExport();
    }

    @Override
    public void onCryptoOperationError(ExportResult result) {
        result.createNotify(getActivity()).show();
        mCachedBackupUri = null;
    }

    @Override
    public void onCryptoOperationCancelled() {
        mCachedBackupUri = null;
    }

    private class SetupServerWithClientCameraTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected void onPreExecute() {
            mSecureDataSocket = new SecureDataSocket(PORT);
            try {
                mConnectionDetails = mSecureDataSocket.prepareServerWithClientCamera();
            } catch (SecureDataSocketException e) {
                e.printStackTrace();
            }
            System.out.println("**DEBUG: " + mConnectionDetails);
            loadQrCode();
            super.onPreExecute();
        }

        @Override
        protected Void doInBackground(Void... unused) {
            System.out.println("**DEBUG: SetupServerWithClientCameraTask.doInBackground()");
            try {
                mSecureDataSocket.setupServerWithClientCamera();
            } catch (SecureDataSocketException e) {
                if (!e.getMessage().contains("Socket closed")) e.printStackTrace();
                else System.out.println("**DEBUG: Socket was closed");
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void voidValue) {
            System.out.println("**DEBUG: SetupServerWithClientCameraTask.onPostExecute()");
            System.out.println("**DEBUG: createExport 1");
            createExport();
            super.onPostExecute(voidValue);
        }

        @Override
        protected void onCancelled() {
            System.out.println("**DEBUG: CANCELED: SetupServerWithClientCameraTask");
            super.onCancelled();
        }
    }

    private class SetupServerNoClientCameraTask extends AsyncTask<Void, Void, String> {
        @Override
        protected void onPreExecute() {
            mSecureDataSocket = new SecureDataSocket(PORT);
            super.onPreExecute();
        }

        @Override
        protected String doInBackground(Void... unused) {
            try {
                return mSecureDataSocket.setupServerNoClientCamera();
            } catch (SecureDataSocketException e) {
                // possible that NullPointerException gets thrown when it's actually Socket closed
                if (!e.getMessage().contains("Socket closed")) e.printStackTrace();
                else System.out.println("**DEBUG: Socket was closed");
            }
            return "ERROR: Could not retrieve Sentence. Please try again.";
        }

        @Override
        protected void onPostExecute(String mPhrase) {
            mSentenceHeadlineText.setVisibility(View.VISIBLE);
            mSentenceText.setVisibility(View.VISIBLE);
            mNoButton.setVisibility(View.VISIBLE);
            mYesButton.setVisibility(View.VISIBLE);
            mSentenceText.setText(mPhrase);
            super.onPostExecute(mPhrase);
        }

        @Override
        protected void onCancelled() {
            System.out.println("**DEBUG: CANCELED: SetupServerNoClientCameraTask");
            mActivity.finish();
            super.onCancelled();
        }
    }
}