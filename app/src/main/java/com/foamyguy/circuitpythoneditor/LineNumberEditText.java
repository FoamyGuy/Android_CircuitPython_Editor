package com.foamyguy.circuitpythoneditor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Created by o_0 on 11/24/2018.
 */

@SuppressLint("AppCompatCustomView")
public class LineNumberEditText extends EditText {
    private TextView lineNumbersText;

    public LineNumberEditText(Context context) {
        super(context);
    }

    public LineNumberEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public LineNumberEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }


    public void setLineNumbersText(TextView lineNumbers){
        this.lineNumbersText = lineNumbers;
    }


    @Override
    protected void onScrollChanged(int horiz, int vert, int oldHoriz, int oldVert) {
        if (lineNumbersText != null){
            lineNumbersText.scrollBy(0, 0-(oldVert-vert));
        }
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert);
    }


}
