package com.appgate.brain.lab
import java.io.File
fun main(args:Array<String>){
 val report=DialogClinic().experiment(File("src/lab/fixtures/dialog-clinic.json"))
 val target=File(args.firstOrNull()?:"build/reports/brain/dialog-clinic.json")
 target.parentFile.mkdirs();target.writeText(report.toString());println(report)
}
