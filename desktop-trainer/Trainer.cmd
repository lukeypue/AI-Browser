@echo off
start "Site Brain Trainer" powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -STA -File "%~dp0Trainer.ps1"
