package com.cloud.dex;

import android.text.TextWatcher;

public abstract class SimpleTextWatcher implements TextWatcher {
    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        // 默认实现
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        // 默认实现
    }

    @Override
    public void afterTextChanged(android.text.Editable s) {
        // 默认实现
    }
}