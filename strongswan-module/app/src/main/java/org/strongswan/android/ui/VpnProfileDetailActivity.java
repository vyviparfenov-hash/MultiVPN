package org.strongswan.android.ui;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/** Заглушка: см. MainActivity.java — используется только как цель PendingIntent. */
public class VpnProfileDetailActivity extends AppCompatActivity {
    public static final String EXTRA_VPN_PROFILE_ID = "vpn_profile_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
