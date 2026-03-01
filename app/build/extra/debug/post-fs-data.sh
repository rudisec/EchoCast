#!/system/bin/sh

# Post-fs-data script - runs early in boot process
# This applies SELinux rules before services start

MODDIR=${0%/*}

# Log start
log -t EchoCast "Post-fs-data: Starting SELinux rules application"

# Find magiskpolicy binary
MAGISKPOLICY=""
for path in /data/adb/magisk/magiskpolicy /sbin/magiskpolicy /system/bin/magiskpolicy /system/xbin/magiskpolicy; do
    if [ -x "$path" ]; then
        MAGISKPOLICY="$path"
        break
    fi
done

if [ -n "$MAGISKPOLICY" ]; then
    log -t EchoCast "Post-fs-data: Using magiskpolicy: $MAGISKPOLICY"
    
    # Apply critical network rules early
    $MAGISKPOLICY --live "allow priv_app dnsproxyd_socket sock_file { create write getattr }" 2>&1 | while read line; do log -t EchoCast "post-fs-data: $line"; done
    $MAGISKPOLICY --live "allow priv_app dnsproxyd_socket unix_dgram_socket { create connect sendto recvfrom }" 2>&1 | while read line; do log -t EchoCast "post-fs-data: $line"; done
    $MAGISKPOLICY --live "allow priv_app dnsproxyd unix_dgram_socket sendto" 2>&1 | while read line; do log -t EchoCast "post-fs-data: $line"; done
    $MAGISKPOLICY --live "allow priv_app self tcp_socket { create connect read write getattr setopt getopt bind }" 2>&1 | while read line; do log -t EchoCast "post-fs-data: $line"; done
    $MAGISKPOLICY --live "allow priv_app node tcp_socket { node_bind name_connect }" 2>&1 | while read line; do log -t EchoCast "post-fs-data: $line"; done
    $MAGISKPOLICY --live "allow priv_app port tcp_socket name_connect" 2>&1 | while read line; do log -t EchoCast "post-fs-data: $line"; done
    
    log -t EchoCast "Post-fs-data: SELinux rules applied"
else
    log -t EchoCast "Post-fs-data: WARNING - magiskpolicy not found"
fi
