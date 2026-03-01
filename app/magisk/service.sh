#!/system/bin/sh

# Service script to apply additional SELinux rules after boot
# This runs after post-fs-data to ensure network access works

MODDIR=${0%/*}

# Wait for system to be ready
sleep 5

# Log start
log -t EchoCast "Starting SELinux rules application"

# Find magiskpolicy binary (should be available in Magisk)
MAGISKPOLICY=""
if [ -f /data/adb/magisk/magiskpolicy ]; then
    MAGISKPOLICY="/data/adb/magisk/magiskpolicy"
elif [ -f /sbin/magiskpolicy ]; then
    MAGISKPOLICY="/sbin/magiskpolicy"
elif [ -f /system/bin/magiskpolicy ]; then
    MAGISKPOLICY="/system/bin/magiskpolicy"
elif [ -f /system/xbin/magiskpolicy ]; then
    MAGISKPOLICY="/system/xbin/magiskpolicy"
fi

if [ -n "$MAGISKPOLICY" ] && [ -x "$MAGISKPOLICY" ]; then
    log -t EchoCast "Using magiskpolicy: $MAGISKPOLICY"
    
    # Apply critical network rules for priv_app
    # DNS resolution
    $MAGISKPOLICY --live "allow priv_app dnsproxyd_socket sock_file { create write getattr }" 2>&1 | while read line; do log -t EchoCast "$line"; done
    $MAGISKPOLICY --live "allow priv_app dnsproxyd_socket unix_dgram_socket { create connect sendto recvfrom }" 2>&1 | while read line; do log -t EchoCast "$line"; done
    $MAGISKPOLICY --live "allow priv_app dnsproxyd unix_dgram_socket sendto" 2>&1 | while read line; do log -t EchoCast "$line"; done
    $MAGISKPOLICY --live "allow priv_app dnsproxyd unix_stream_socket connectto" 2>&1 | while read line; do log -t EchoCast "$line"; done
    
    # Network capabilities
    $MAGISKPOLICY --live "allow priv_app priv_app capability { net_raw net_admin }" 2>&1 | while read line; do log -t EchoCast "$line"; done
    
    # Socket creation and connection
    $MAGISKPOLICY --live "allow priv_app self tcp_socket { create connect read write getattr setopt getopt bind }" 2>&1 | while read line; do log -t EchoCast "$line"; done
    $MAGISKPOLICY --live "allow priv_app self udp_socket { create connect read write getattr setopt bind }" 2>&1 | while read line; do log -t EchoCast "$line"; done
    
    # Hostname resolution
    $MAGISKPOLICY --live "allow priv_app node tcp_socket { node_bind name_connect }" 2>&1 | while read line; do log -t EchoCast "$line"; done
    $MAGISKPOLICY --live "allow priv_app node udp_socket { node_bind name_connect }" 2>&1 | while read line; do log -t EchoCast "$line"; done
    $MAGISKPOLICY --live "allow priv_app port tcp_socket name_connect" 2>&1 | while read line; do log -t EchoCast "$line"; done
    
    # Netlink sockets for network configuration
    $MAGISKPOLICY --live "allow priv_app self netlink_route_socket { create read write getattr nlmsg_read }" 2>&1 | while read line; do log -t EchoCast "$line"; done
    $MAGISKPOLICY --live "allow priv_app self netlink_socket { create read write getattr }" 2>&1 | while read line; do log -t EchoCast "$line"; done
    
    # Netd communication
    $MAGISKPOLICY --live "allow priv_app netd fd use" 2>&1 | while read line; do log -t EchoCast "$line"; done
    $MAGISKPOLICY --live "allow priv_app netd unix_stream_socket { create read write getattr connect }" 2>&1 | while read line; do log -t EchoCast "$line"; done
    $MAGISKPOLICY --live "allow priv_app netd unix_dgram_socket { create read write getattr connect sendto }" 2>&1 | while read line; do log -t EchoCast "$line"; done
    
    # Fwmarkd for network routing
    $MAGISKPOLICY --live "allow priv_app fwmarkd_socket sock_file { create write getattr }" 2>&1 | while read line; do log -t EchoCast "$line"; done
    $MAGISKPOLICY --live "allow priv_app fwmarkd unix_stream_socket connectto" 2>&1 | while read line; do log -t EchoCast "$line"; done
    
    log -t EchoCast "SELinux rules applied successfully using magiskpolicy"
else
    log -t EchoCast "WARNING: magiskpolicy not found, trying alternative methods"
    
    # Fallback: Try using supolicy if available
    if [ -f "$MODDIR/supolicy" ] && [ -x "$MODDIR/supolicy" ]; then
        log -t EchoCast "Using supolicy fallback"
        $MODDIR/supolicy --live "allow priv_app priv_app capability { net_raw net_admin }" 2>&1 | while read line; do log -t EchoCast "$line"; done
        $MODDIR/supolicy --live "allow priv_app dnsproxyd_socket unix_dgram_socket sendto" 2>&1 | while read line; do log -t EchoCast "$line"; done
        $MODDIR/supolicy --live "allow priv_app node tcp_socket node_bind" 2>&1 | while read line; do log -t EchoCast "$line"; done
        $MODDIR/supolicy --live "allow priv_app port tcp_socket name_connect" 2>&1 | while read line; do log -t EchoCast "$line"; done
    fi
    
    # Last resort: Check if we can at least verify SELinux status
    SELINUX_STATUS=$(getenforce 2>/dev/null || echo "unknown")
    log -t EchoCast "SELinux status: $SELINUX_STATUS"
    
    # Only set permissive mode as absolute last resort (INSECURE)
    # Uncomment only if absolutely necessary for testing
    # log -t EchoCast "WARNING: Setting SELinux to permissive mode (INSECURE)"
    # setenforce 0 2>/dev/null || true
fi

log -t EchoCast "SELinux rules application completed"
