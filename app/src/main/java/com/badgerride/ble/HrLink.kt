package com.badgerride.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context

/**
 * One heart-rate strap connection. No op queue: the strap issues exactly one GATT
 * op (the CCCD write) for its whole lifetime, and the one-op-at-a-time rule is per
 * connection. Don't route it through the trainer's queue - a shared queue would
 * let the strap's callback complete a trainer op.
 */
@SuppressLint("MissingPermission")
internal class HrLink(
    private val context: Context,
    val device: BluetoothDevice,
    private val hub: BleCentral,
) {
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var closing = false

    private fun log(msg: String) = hub.log(msg)

    fun connect() {
        closing = false
        log("Connecting to HR sensor ${device.name ?: device.address}…")
        gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        closing = true
        gatt?.close()
        gatt = null
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("HR sensor connected, discovering services")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log(if (status == BluetoothGatt.GATT_SUCCESS) "HR disconnected"
                    else "HR connection lost (status=$status)")
                this@HrLink.gatt = null
                gatt.close()
                if (!closing) hub.onHrDown(this@HrLink)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { log("HR service discovery failed (status=$status)"); return }
            val ch = gatt.getService(Ftms.HR_SERVICE)?.getCharacteristic(Ftms.HR_MEASUREMENT)
                ?: run { log("HR sensor has no Heart Rate Measurement characteristic"); return }
            log("HR measurement characteristic found, enabling notifications")
            hub.onHrUp(this@HrLink)
            val cccd = ch.getDescriptor(Ftms.CCCD) ?: run { log("HR characteristic has no CCCD"); return }
            if (!gatt.setCharacteristicNotification(ch, true)) { log("HR setCharacteristicNotification failed"); return }
            val rc = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (rc != BluetoothStatusCodes.SUCCESS) log("HR CCCD write did not start (rc=$rc)")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("HR CCCD write failed (status=$status)")
            else log("HR notifications enabled")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != Ftms.HR_MEASUREMENT) return
            // flags uint8, then uint8 or uint16 bpm depending on bit 0
            val wide = value.isNotEmpty() && value[0].toInt() and 0x01 != 0
            val need = if (wide) 3 else 2
            if (value.size < need) { log("Short HR packet (${value.size} B) - ignored"); return }
            val hr = if (wide) (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
                     else value[1].toInt() and 0xFF
            hub.lastHr = hr
            hub.lastHrAt = System.currentTimeMillis()
            hub.onLiveChanged()
        }
    }
}
