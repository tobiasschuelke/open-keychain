package org.sufficientlysecure.keychain.ui;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
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

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.network.KeyExportSocket;
import org.sufficientlysecure.keychain.operations.results.ExportResult;
import org.sufficientlysecure.keychain.provider.TemporaryFileProvider;
import org.sufficientlysecure.keychain.service.BackupKeyringParcel;
import org.sufficientlysecure.keychain.service.input.CryptoInputParcel;
import org.sufficientlysecure.keychain.ui.base.CryptoOperationFragment;
import org.sufficientlysecure.keychain.ui.util.QrCodeUtils;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PrivateKeyExportFragment extends CryptoOperationFragment<BackupKeyringParcel, ExportResult> implements KeyExportSocket.ExportKeyListener {
    public static final String ARG_MASTER_KEY_IDS = "master_key_ids";

    private static final String ARG_CONNECTION_DETAILS = "connection_details";
    private static final String ARG_IP_ADDRESS = "ip_address";
    private static final String ARG_MANUAL_MODE = "manual_mode";
    private static final String ARG_PHRASE = "phrase";

    private ImageView mQrCode;
    private TextView mSentenceText;
    private TextView mSentenceHeadlineText;
    private Button mNoButton;
    private Button mYesButton;
    private View mQrLayout;
    private Button mButton;
    private Button mHelpButton;
    private View mInfoLayout;

    private Activity mActivity;
    private String mIpAddress;
    private String mConnectionDetails;
    private long mMasterKeyId;
    private Uri mCachedUri;
    private boolean mManuelMode;
    private String mPhrase;

    private KeyExportSocket mSocket;

    public static PrivateKeyExportFragment newInstance(long masterKeyId) {
        PrivateKeyExportFragment frag = new PrivateKeyExportFragment();

        Bundle args = new Bundle();
        args.putLong(ARG_MASTER_KEY_IDS, masterKeyId);
        frag.setArguments(args);

        return frag;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        mActivity = (Activity) context;
        mIpAddress = getIPAddress(true);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mSocket = KeyExportSocket.getInstance(this);

        if (savedInstanceState != null) {
            mConnectionDetails = savedInstanceState.getString(ARG_CONNECTION_DETAILS);
            mIpAddress = savedInstanceState.getString(ARG_IP_ADDRESS);
            mManuelMode = savedInstanceState.getBoolean(ARG_MANUAL_MODE, false);
            mPhrase = savedInstanceState.getString(ARG_PHRASE);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putString(ARG_CONNECTION_DETAILS, mConnectionDetails);
        outState.putString(ARG_IP_ADDRESS, mIpAddress);
        outState.putString(ARG_PHRASE, mPhrase);
        outState.putBoolean(ARG_MANUAL_MODE, mManuelMode);
    }

    @Override
    public void onStop() {
        super.onStop();

        if (mActivity.isFinishing()) {
            mSocket.close();
        }
    }

    @Override
    public View onCreateView(LayoutInflater i, ViewGroup c, Bundle savedInstanceState) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.private_key_export_fragment, c, false);

        Bundle args = getArguments();
        mMasterKeyId = args.getLong(ARG_MASTER_KEY_IDS);

        mQrLayout = view.findViewById(R.id.private_key_export_qr_layout);
        mQrCode = (ImageView) view.findViewById(R.id.private_key_export_qr_image);
        mButton = (Button) view.findViewById(R.id.private_key_export_button);
        mHelpButton = (Button) view.findViewById(R.id.private_key_export_help_button);

        mInfoLayout = view.findViewById(R.id.private_key_export_info_layout);
        TextView ipText = (TextView) view.findViewById(R.id.private_key_export_ip);
        TextView portText = (TextView) view.findViewById(R.id.private_key_export_port);
        mSentenceHeadlineText = (TextView) view.findViewById(R.id.private_key_export_sentence_headline);
        mSentenceText = (TextView) view.findViewById(R.id.private_key_export_sentence);
        mNoButton = (Button) view.findViewById(R.id.private_key_export_sentence_not_matched_button);
        mYesButton = (Button) view.findViewById(R.id.private_key_export_sentence_matched_button);

        mQrCode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showQrCodeDialog();
            }
        });

        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mSocket.manualMode();

                mManuelMode = true;
                showManualMode();
            }
        });

        mHelpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                HelpActivity.startHelpActivity(mActivity, HelpActivity.TAB_FAQ, R.string.help_tab_faq_headline_transfer_key);
            }
        });

        ipText.setText(mIpAddress);
        portText.setText(String.valueOf(mSocket.getPort()));

        mNoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mSocket.exportPhrasesMatched(false);
            }
        });

        mYesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mSocket.exportPhrasesMatched(true);
            }
        });

        if (mManuelMode) {
            showManualMode();

            if (mPhrase != null) {
                showPhrase();
            }
        } else {
            loadQrCode();
        }

        return view;
    }

    private void showManualMode() {
        mQrLayout.setVisibility(View.GONE);
        mButton.setVisibility(View.GONE);
        mHelpButton.setVisibility(View.GONE);

        mInfoLayout.setVisibility(View.VISIBLE);
    }

    private void showPhrase() {
        mSentenceHeadlineText.setVisibility(View.VISIBLE);
        mSentenceText.setVisibility(View.VISIBLE);
        mNoButton.setVisibility(View.VISIBLE);
        mYesButton.setVisibility(View.VISIBLE);

        mSentenceText.setText(mPhrase);
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

        if (mCachedUri == null) {
            mCachedUri = TemporaryFileProvider.createFile(mActivity, filename,
                    Constants.MIME_TYPE_ENCRYPTED_ALTERNATE);

            cryptoOperation(new CryptoInputParcel());
            return;
        }

        mSocket.writeKey(mActivity, mCachedUri);
    }

    /**
     * from: http://stackoverflow.com/a/13007325
     *
     * Get IP address from first non-localhost interface
     * @param useIPv4  true=return ipv4, false=return ipv6
     * @return  address or empty string
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
                        boolean isIPv4 = sAddr.indexOf(':')<0;

                        if (useIPv4) {
                            if (isIPv4)
                                return sAddr;
                        } else {
                            if (!isIPv4) {
                                int delim = sAddr.indexOf('%'); // drop ip6 zone suffix
                                return delim<0 ? sAddr.toUpperCase() : sAddr.substring(0, delim).toUpperCase();
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) { } // for now eat exceptions
        return "";
    }

    @Nullable
    @Override
    public BackupKeyringParcel createOperationInput() {
        return new BackupKeyringParcel(new long[] {mMasterKeyId}, true, false, false, mCachedUri);
    }

    @Override
    public void onCryptoOperationSuccess(ExportResult result) {
        createExport();
    }

    @Override
    public void onCryptoOperationError(ExportResult result) {
        result.createNotify(getActivity()).show();
        mCachedUri = null;
    }

    @Override
    public void onCryptoOperationCancelled() {
        mCachedUri = null;
    }

    @Override
    public void showConnectionDetails(String connectionDetails) {
        mConnectionDetails = connectionDetails;
        loadQrCode();
    }

    @Override
    public void loadKey() {
        createExport();
    }

    @Override
    public void keyExported() {
        mActivity.finish();
    }

    @Override
    public void showPhrase(String phrase) {
        mPhrase = phrase;
        showPhrase();
    }
}
