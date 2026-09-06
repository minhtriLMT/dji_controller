package com.tri.djicontrol.connection;

import android.content.Context;
import android.util.Log;

import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKInitEvent;
import dji.sdk.sdkmanager.DJISDKManager;

public class DJIConnectionManager {
    private static final String TAG = "DJIConnectionManager";
    private static DJIConnectionManager instance;
    private ConnectionListener listener;
    private BaseProduct currentProduct;

    public interface ConnectionListener {
        void onStatusChange(String status);
    }

    public static DJIConnectionManager getInstance() {
        if (instance == null) instance = new DJIConnectionManager();
        return instance;
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.listener = listener;
    }

    public void startConnection(Context context) {
        notifyStatus("Đang đăng ký DJI SDK...");

        DJISDKManager.getInstance().registerApp(context, new DJISDKManager.SDKManagerCallback() {
            @Override
            public void onRegister(DJIError djiError) {
                if (djiError == DJISDKError.REGISTRATION_SUCCESS) {
                    notifyStatus("Đăng ký thành công! Đang tìm Drone...");
                    DJISDKManager.getInstance().startConnectionToProduct();
                } else {
                    notifyStatus("Lỗi SDK: " + djiError.getDescription());
                }
            }

            @Override
            public void onProductDisconnect() {
                currentProduct = null;
                notifyStatus("Drone mất kết nối!");
            }

            @Override
            public void onProductConnect(BaseProduct baseProduct) {
                currentProduct = baseProduct;
                if (baseProduct != null && baseProduct.getModel() != null) {
                    notifyStatus("Đã kết nối: " + baseProduct.getModel().getDisplayName());
                }
            }

            @Override
            public void onProductChanged(BaseProduct baseProduct) {
                currentProduct = baseProduct;
                Log.d(TAG, "Thiết bị thay đổi trạng thái kết nối");
            }

            @Override
            public void onComponentChange(BaseProduct.ComponentKey componentKey, BaseComponent baseComponent, BaseComponent baseComponent1) {

            }

            @Override
            public void onInitProcess(DJISDKInitEvent djisdkInitEvent, int i) {

            }

            @Override
            public void onDatabaseDownloadProgress(long l, long l1) {

            }
        });
    }

    // Thêm các hàm hỗ trợ lấy nhanh FlightController hoặc Product cho các module khác
    public BaseProduct getProduct() {
        return currentProduct != null ? currentProduct : DJISDKManager.getInstance().getProduct();
    }

    public FlightController getFlightController() {
        BaseProduct product = getProduct();
        if (product instanceof Aircraft) {
            return ((Aircraft) product).getFlightController();
        }
        return null;
    }

    private void notifyStatus(String status) {
        if (listener != null) listener.onStatusChange(status);
    }
}