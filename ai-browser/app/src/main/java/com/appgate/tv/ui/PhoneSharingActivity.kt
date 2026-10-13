package com.appgate.tv.ui

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.appgate.tv.store.PhoneSharing
import java.util.concurrent.Executors

class PhoneSharingActivity:AppCompatActivity(){
 private lateinit var sharing:PhoneSharing
 private lateinit var status:TextView
 private val handler=Handler(Looper.getMainLooper())
 private val refresh=object:Runnable{override fun run(){status.text=sharing.status();handler.postDelayed(this,2000)}}
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState);title="Phone test diagnostics";sharing=PhoneSharing(this)
  val column=Ui.column(this)
  column.addView(Ui.text(this,"Automatic phone test diagnostics",22f,Ui.text,true))
  column.addView(Ui.text(this,"Sharing starts OFF. If enabled and paired, completed phone tests send only event codes, canonical site IDs, enums, bounded counts and timing over Wi-Fi or mobile data. Queries, page text, URLs, cookies, credentials and screenshots are excluded.",14f,Ui.text))
  column.addView(Ui.text(this,"The receiver retains reports for 30 days. Private Git history may retain sanitized copies longer. Turning sharing off cancels sending and clears unsent copies; already received reports cannot be recalled. Pending reports expire after seven days (maximum 10 reports / 10 MiB).",14f,Ui.muted))
  val consent=CheckBox(this).apply{text="I consent to automatic sanitized phone-test sharing";setTextColor(Ui.text);isChecked=sharing.enabled()}
  consent.setOnCheckedChangeListener{_,on->
   if(!on)sharing.setEnabled(false)
   else AlertDialog.Builder(this).setTitle("Enable phone diagnostic sharing?").setMessage("Only sanitized test evidence will be sent to reports.ryzod.com. Mobile data may be used. You can turn it off here at any time.").setPositiveButton("I agree"){_,_->sharing.setEnabled(true)}.setNegativeButton("Cancel"){_,_->consent.isChecked=false}.setOnCancelListener{consent.isChecked=false}.show()
  }
  column.addView(consent)
  val code=EditText(this).apply{hint="Single-use pairing code";setSingleLine();setTextColor(Ui.text)};column.addView(code)
  column.addView(Ui.button(this,"PAIR THIS PHONE"){
   val value=code.text.toString().trim();code.setText("")
   Executors.newSingleThreadExecutor().apply{execute{sharing.pair(value);shutdown()}}
  })
  status=Ui.text(this,sharing.status(),13f,Ui.text);column.addView(status)
  setContentView(ScrollView(this).apply{setBackgroundColor(Ui.bg);addView(column)})
 }
 override fun onResume(){super.onResume();handler.post(refresh)}
 override fun onPause(){handler.removeCallbacks(refresh);super.onPause()}
}
