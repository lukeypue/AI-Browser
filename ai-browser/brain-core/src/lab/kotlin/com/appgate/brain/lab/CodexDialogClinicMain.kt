package com.appgate.brain.lab
import java.io.File
object CodexDialogClinicMain {
 @JvmStatic fun main(args:Array<String>){
  require(args.size==2)
  val r=CodexDialogClinic(File(args[0]).readText()).experiment(File("src/lab/fixtures/dialog-clinic.json"))
  require(r.optInt("compiled_transfer_uses")==4 && r.optInt("false_mastery")==0 && r.optInt("behavior_passes")==10)
  File(args[1]).writeText(r.toString()+"\n");println("AI proposal verified through engine/compiler; held-out transfer4, false mastery0")
 }
}
