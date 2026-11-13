package io.github.lemonrsh.frontflashqs;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * The hidden helper app's only component. SystemUI binds this service for the Quick Settings
 * tile; it selects a front-facing camera that advertises a flash unit and asks CameraService to
 * change its torch state. The accompanying Magisk module grants CAMERA before it adds this tile.
 */
public final class FrontFlashTileService extends TileService {

    private enum Availability {
        READY,
        NO_FRONT_FLASH,
        CAMERA_BUSY,
        PERMISSION_MISSING
    }

    private CameraManager cameraManager;
    private String frontFlashCameraId;
    private boolean torchOn;
    private boolean torchAvailable;
    private boolean callbackRegistered;
    private Availability availability = Availability.NO_FRONT_FLASH;

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (!cameraId.equals(frontFlashCameraId)) {
                return;
            }
            torchOn = enabled;
            torchAvailable = true;
            availability = Availability.READY;
            updateTile();
        }

        @Override
        public void onTorchModeUnavailable(String cameraId) {
            if (!cameraId.equals(frontFlashCameraId)) {
                return;
            }
            torchOn = false;
            torchAvailable = false;
            availability = Availability.CAMERA_BUSY;
            updateTile();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        cameraManager = getSystemService(CameraManager.class);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        findFrontFlashCamera();
        registerTorchCallback();
        updateTile();
    }

    @Override
    public void onStopListening() {
        unregisterTorchCallback();
        super.onStopListening();
    }

    @Override
    public void onDestroy() {
        unregisterTorchCallback();
        super.onDestroy();
    }

    @Override
    public void onClick() {
        super.onClick();
        findFrontFlashCamera();
        if (frontFlashCameraId == null || availability == Availability.PERMISSION_MISSING) {
            updateTile();
            return;
        }

        try {
            cameraManager.setTorchMode(frontFlashCameraId, !torchOn);
        } catch (CameraAccessException | IllegalArgumentException error) {
            torchOn = false;
            torchAvailable = false;
            availability = Availability.CAMERA_BUSY;
            updateTile();
        } catch (SecurityException error) {
            torchOn = false;
            torchAvailable = false;
            availability = Availability.PERMISSION_MISSING;
            updateTile();
        }
    }

    private void findFrontFlashCamera() {
        if (cameraManager == null) {
            frontFlashCameraId = null;
            availability = Availability.NO_FRONT_FLASH;
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            frontFlashCameraId = null;
            torchOn = false;
            torchAvailable = false;
            availability = Availability.PERMISSION_MISSING;
            return;
        }

        try {
            String candidate = null;
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                Boolean flashAvailable = characteristics.get(
                        CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (facing != null
                        && facing == CameraCharacteristics.LENS_FACING_FRONT
                        && Boolean.TRUE.equals(flashAvailable)) {
                    candidate = cameraId;
                    break;
                }
            }
            frontFlashCameraId = candidate;
            if (candidate == null) {
                torchOn = false;
                torchAvailable = false;
                availability = Availability.NO_FRONT_FLASH;
            } else if (availability != Availability.CAMERA_BUSY) {
                // The callback will replace this optimistic OFF state with the actual torch state.
                torchAvailable = true;
                availability = Availability.READY;
            }
        } catch (CameraAccessException error) {
            frontFlashCameraId = null;
            torchOn = false;
            torchAvailable = false;
            availability = Availability.CAMERA_BUSY;
        } catch (SecurityException error) {
            frontFlashCameraId = null;
            torchOn = false;
            torchAvailable = false;
            availability = Availability.PERMISSION_MISSING;
        }
    }

    private void registerTorchCallback() {
        if (cameraManager == null || callbackRegistered) {
            return;
        }
        try {
            cameraManager.registerTorchCallback(torchCallback, new Handler(Looper.getMainLooper()));
            callbackRegistered = true;
        } catch (SecurityException error) {
            availability = Availability.PERMISSION_MISSING;
        }
    }

    private void unregisterTorchCallback() {
        if (cameraManager == null || !callbackRegistered) {
            return;
        }
        try {
            cameraManager.unregisterTorchCallback(torchCallback);
        } catch (IllegalArgumentException ignored) {
            // CameraService has already discarded this callback.
        }
        callbackRegistered = false;
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }

        tile.setLabel(getString(R.string.tile_label));
        switch (availability) {
            case READY:
                tile.setState(torchOn ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
                tile.setContentDescription(getString(R.string.tile_label));
                break;
            case PERMISSION_MISSING:
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.setContentDescription(getString(R.string.tile_permission_missing));
                break;
            case CAMERA_BUSY:
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.setContentDescription(getString(R.string.tile_camera_busy));
                break;
            case NO_FRONT_FLASH:
            default:
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.setContentDescription(getString(R.string.tile_unavailable));
                break;
        }
        tile.updateTile();
    }
}
