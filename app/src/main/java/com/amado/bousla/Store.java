package com.amado.bousla;

import android.content.Context;
import android.content.SharedPreferences;

final class Store {
    static SharedPreferences p(Context c) {
        return c.getSharedPreferences("bousla", Context.MODE_PRIVATE);
    }
}
