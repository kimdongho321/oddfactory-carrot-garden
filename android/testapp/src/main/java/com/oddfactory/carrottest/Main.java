package com.oddfactory.carrottest;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** 캬롯 앱 텃밭에서 '설치 삭제'를 시험해 보려고 까는 빈 앱. 아무 기능 없음. */
public class Main extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setText("캬롯 앱 텃밭 테스트용 빈 앱이에요.\n텃밭에서 뽑아(삭제해) 보세요.");
        t.setTextSize(20); t.setPadding(48, 96, 48, 48);
        setContentView(t);
    }
}
