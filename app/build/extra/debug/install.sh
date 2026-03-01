#!/sbin/sh

# EchoCast - Legacy installer (custom UI, no Magisk branding)

print_modname() {
  ui_print " "
  ui_print "  ========================================"
  ui_print "  |          E C H O C A S T            |"
  ui_print "  |  Your soundboard, in every call.    |"
  ui_print "  ========================================"
  ui_print " "
}

on_install() {
  ui_print "  >> Installing EchoCast..."
  unzip -o "$ZIPFILE" -x 'META-INF/*' 'install.sh' -d "$MODPATH" 2>/dev/null
  ui_print "  [*] EchoCast installed successfully!"
  ui_print "  [*] Reboot to activate."
  ui_print " "
}

set_permissions() {
  set_default_perm $MODPATH
}
