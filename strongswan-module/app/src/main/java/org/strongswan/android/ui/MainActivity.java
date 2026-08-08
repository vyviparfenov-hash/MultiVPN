package org.strongswan.android.ui;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/** Заглушка: реальный экран не используется (свой UI пишем отдельно),
 *  класс нужен только как валидная цель PendingIntent для CharonVpnService/VpnTileService. */
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
