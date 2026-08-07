package com.cliftonia.fs42tv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Placeholder so the toolchain can be proven before any logic exists. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "FieldStation42"
            setTextColor(Color.parseColor("#33FF33"))
            setBackgroundColor(Color.BLACK)
            textSize = 48f
            gravity = Gravity.CENTER
        })
    }
}
