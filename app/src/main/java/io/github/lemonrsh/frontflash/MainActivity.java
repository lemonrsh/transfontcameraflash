package io.github.lemonrsh.frontflash;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** A small, explicit test for front-facing camera torch support. */
public final class MainActivity extends Activity {
    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final long REQUEST_TIMEOUT_MS = 3500L;
    private static final long STALE_CALLBACK_GRACE_MS = 1200L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<CameraTarget> frontFlashCameras = new ArrayList<>();

    private CameraManager cameraManager;
    private Spinner cameraSpinner;
    private ArrayAdapter<CameraTarget> cameraAdapter;
    private TextView permissionInfo;
    private TextView cameraInfo;
    private TextView statusBadge;
    private TextView statusDetail;
    private Button permissionButton;
    private Button rescanButton;
    private Button onButton;
    private Button offButton;

    private String selectedCameraId;
    private String scanMessage;
    private String operationError;
    private String permissionMessage;
    private boolean cameraListScanned;
    private boolean lastPermissionGranted;
    private boolean callbackRegistered;
    private boolean suppressSpinnerCallbacks;
    private boolean torchStateKnown;
    private boolean torchEnabled;
    private boolean torchUnavailable;
    private boolean ownsTorchRequest;
    private boolean requestPending;
    private boolean requestedTorchState;
    private long requestStartedAt;
    private Runnable requestTimeout;

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            mainHandler.post(() -> {
                if (selectedCameraId == null || !selectedCameraId.equals(cameraId)) {
                    return;
                }

                torchStateKnown = true;
                torchEnabled = enabled;
                torchUnavailable = false;

                if (requestPending) {
                    if (enabled == requestedTorchState) {
                        clearPendingRequest();
                        operationError = null;
                    } else if (SystemClock.elapsedRealtime() - requestStartedAt >= STALE_CALLBACK_GRACE_MS) {
                        finishUnconfirmedRequest();
                    }
                }
                render();
            });
        }

        @Override
        public void onTorchModeUnavailable(String cameraId) {
            mainHandler.post(() -> {
                if (selectedCameraId == null || !selectedCameraId.equals(cameraId)) {
                    return;
                }
                clearPendingRequest();
                torchStateKnown = false;
                torchUnavailable = true;
                ownsTorchRequest = false;
                operationError = null;
                render();
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        cameraSpinner = findViewById(R.id.camera_spinner);
        permissionInfo = findViewById(R.id.permission_info);
        cameraInfo = findViewById(R.id.camera_info);
        statusBadge = findViewById(R.id.status_badge);
        statusDetail = findViewById(R.id.status_detail);
        permissionButton = findViewById(R.id.permission_button);
        rescanButton = findViewById(R.id.rescan_button);
        onButton = findViewById(R.id.turn_on_button);
        offButton = findViewById(R.id.turn_off_button);

        cameraAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new ArrayList<>());
        cameraAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cameraSpinner.setAdapter(cameraAdapter);
        cameraSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressSpinnerCallbacks || position < 0 || position >= frontFlashCameras.size()) {
                    return;
                }
                selectCamera(frontFlashCameras.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // No action; the scan result below reports when no camera is available.
            }
        });

        permissionButton.setOnClickListener(view -> onPermissionButtonPressed());
        rescanButton.setOnClickListener(view -> refreshCameras());
        onButton.setOnClickListener(view -> requestTorchMode(true));
        offButton.setOnClickListener(view -> requestTorchMode(false));

        permissionMessage = getString(R.string.permission_needed_explanation);
        scanMessage = getString(R.string.scan_not_run);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean granted = hasCameraPermission();
        if (granted) {
            registerTorchCallback();
            if (!lastPermissionGranted || !cameraListScanned) {
                refreshCameras();
            }
            lastPermissionGranted = true;
        } else {
            unregisterTorchCallback();
            if (lastPermissionGranted) {
                frontFlashCameras.clear();
                cameraAdapter.clear();
                cameraAdapter.notifyDataSetChanged();
                selectedCameraId = null;
                cameraListScanned = false;
                torchStateKnown = false;
                torchUnavailable = false;
                ownsTorchRequest = false;
                clearPendingRequest();
            }
            lastPermissionGranted = false;
            render();
        }
    }

    @Override
    protected void onPause() {
        turnOffOurTorchBeforeLeaving();
        unregisterTorchCallback();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA_PERMISSION) {
            return;
        }

        if (hasCameraPermission()) {
            permissionMessage = getString(R.string.permission_granted_explanation);
            lastPermissionGranted = true;
            registerTorchCallback();
            refreshCameras();
        } else {
            permissionMessage = getString(R.string.permission_denied_explanation);
            lastPermissionGranted = false;
            frontFlashCameras.clear();
            cameraAdapter.clear();
            cameraAdapter.notifyDataSetChanged();
            selectedCameraId = null;
            cameraListScanned = false;
            torchStateKnown = false;
            torchEnabled = false;
            torchUnavailable = false;
            ownsTorchRequest = false;
            clearPendingRequest();
            render();
        }
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void onPermissionButtonPressed() {
        if (hasCameraPermission()) {
            refreshCameras();
            return;
        }

        permissionMessage = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                ? getString(R.string.permission_rationale)
                : getString(R.string.permission_needed_explanation);
        render();
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
    }

    private void registerTorchCallback() {
        if (callbackRegistered || cameraManager == null || !hasCameraPermission()) {
            return;
        }
        try {
            cameraManager.registerTorchCallback(torchCallback, mainHandler);
            callbackRegistered = true;
        } catch (SecurityException exception) {
            operationError = getString(R.string.permission_error, safeMessage(exception));
            render();
        }
    }

    private void unregisterTorchCallback() {
        if (!callbackRegistered || cameraManager == null) {
            return;
        }
        try {
            cameraManager.unregisterTorchCallback(torchCallback);
        } catch (SecurityException ignored) {
            // Permission may have been revoked while the activity was in the background.
        }
        callbackRegistered = false;
    }

    private void refreshCameras() {
        if (!hasCameraPermission()) {
            render();
            return;
        }
        if (cameraManager == null) {
            scanMessage = getString(R.string.camera_service_missing);
            cameraListScanned = true;
            frontFlashCameras.clear();
            selectedCameraId = null;
            render();
            return;
        }

        final String previousSelection = selectedCameraId;
        final List<CameraTarget> found = new ArrayList<>();
        int foundFrontCount = 0;
        int scanFailures = 0;
        String fatalScanMessage = null;
        operationError = null;
        scanMessage = null;
        torchUnavailable = false;

        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                try {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        foundFrontCount++;
                        Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                        if (Boolean.TRUE.equals(flashAvailable)) {
                            found.add(new CameraTarget(cameraId));
                        }
                    }
                } catch (CameraAccessException | IllegalArgumentException | SecurityException exception) {
                    scanFailures++;
                }
            }
        } catch (CameraAccessException | SecurityException exception) {
            fatalScanMessage = getString(R.string.camera_scan_failed, safeMessage(exception));
            found.clear();
            foundFrontCount = 0;
        }

        cameraListScanned = true;

        boolean previousSelectionStillPresent = false;
        if (previousSelection != null) {
            for (CameraTarget target : found) {
                if (previousSelection.equals(target.id)) {
                    previousSelectionStillPresent = true;
                    break;
                }
            }
        }
        if (!previousSelectionStillPresent && ownsTorchRequest && previousSelection != null) {
            safelyTurnOff(previousSelection);
        }

        frontFlashCameras.clear();
        frontFlashCameras.addAll(found);
        CameraTarget selectedTarget = null;
        if (previousSelectionStillPresent) {
            for (CameraTarget target : frontFlashCameras) {
                if (previousSelection.equals(target.id)) {
                    selectedTarget = target;
                    break;
                }
            }
        }
        if (selectedTarget == null && !frontFlashCameras.isEmpty()) {
            selectedTarget = frontFlashCameras.get(0);
        }
        selectedCameraId = selectedTarget == null ? null : selectedTarget.id;

        if (!previousSelectionStillPresent) {
            clearPendingRequest();
            torchStateKnown = false;
            torchEnabled = false;
            torchUnavailable = false;
            ownsTorchRequest = false;
        }

        suppressSpinnerCallbacks = true;
        cameraAdapter.clear();
        cameraAdapter.addAll(frontFlashCameras);
        cameraAdapter.notifyDataSetChanged();
        int selectedPosition = frontFlashCameras.indexOf(selectedTarget);
        if (selectedPosition >= 0) {
            cameraSpinner.setSelection(selectedPosition, false);
        }
        suppressSpinnerCallbacks = false;

        if (frontFlashCameras.isEmpty()) {
            if (fatalScanMessage != null) {
                scanMessage = fatalScanMessage;
            } else if (foundFrontCount > 0) {
                scanMessage = getString(R.string.front_cameras_without_flash, foundFrontCount);
            } else if (scanFailures > 0) {
                scanMessage = getString(R.string.camera_scan_partial_failure);
            } else {
                scanMessage = getString(R.string.no_front_camera);
            }
        } else if (scanFailures > 0) {
            scanMessage = getString(R.string.front_flash_found_partial, frontFlashCameras.size(), scanFailures);
        } else {
            scanMessage = getString(R.string.front_flash_found, frontFlashCameras.size());
        }

        render();
    }

    private void selectCamera(CameraTarget target) {
        if (target == null || target.id.equals(selectedCameraId)) {
            return;
        }

        String previousId = selectedCameraId;
        if (ownsTorchRequest && previousId != null) {
            safelyTurnOff(previousId);
        }
        selectedCameraId = target.id;
        clearPendingRequest();
        operationError = null;
        torchStateKnown = false;
        torchEnabled = false;
        torchUnavailable = false;
        ownsTorchRequest = false;
        render();
    }

    private void requestTorchMode(boolean enabled) {
        if (!hasCameraPermission()) {
            permissionMessage = getString(R.string.permission_needed_explanation);
            render();
            return;
        }
        if (selectedCameraId == null || cameraManager == null) {
            render();
            return;
        }

        operationError = null;
        torchUnavailable = false;
        ownsTorchRequest = enabled;
        clearPendingRequest();

        if (torchStateKnown && torchEnabled == enabled) {
            render();
            return;
        }

        requestPending = true;
        requestedTorchState = enabled;
        requestStartedAt = SystemClock.elapsedRealtime();
        requestTimeout = () -> {
            if (!requestPending) {
                return;
            }
            finishUnconfirmedRequest();
            render();
        };
        mainHandler.postDelayed(requestTimeout, REQUEST_TIMEOUT_MS);
        render();

        try {
            cameraManager.setTorchMode(selectedCameraId, enabled);
        } catch (CameraAccessException exception) {
            clearPendingRequest();
            ownsTorchRequest = false;
            operationError = getString(R.string.camera_access_error, safeMessage(exception));
            render();
        } catch (IllegalArgumentException exception) {
            clearPendingRequest();
            ownsTorchRequest = false;
            operationError = getString(R.string.front_flash_not_controllable);
            render();
        } catch (SecurityException exception) {
            clearPendingRequest();
            ownsTorchRequest = false;
            operationError = getString(R.string.permission_error, safeMessage(exception));
            render();
        }
    }

    private void finishUnconfirmedRequest() {
        boolean requestedOn = requestedTorchState;
        boolean actualStateDiffers = !torchStateKnown || torchEnabled != requestedTorchState;
        clearPendingRequest();
        if (requestedOn && actualStateDiffers) {
            ownsTorchRequest = false;
        }
        if (actualStateDiffers) {
            operationError = getString(R.string.no_torch_confirmation);
        }
    }

    private void clearPendingRequest() {
        requestPending = false;
        if (requestTimeout != null) {
            mainHandler.removeCallbacks(requestTimeout);
            requestTimeout = null;
        }
    }

    private void safelyTurnOff(String cameraId) {
        if (cameraManager == null || !hasCameraPermission()) {
            return;
        }
        try {
            cameraManager.setTorchMode(cameraId, false);
        } catch (CameraAccessException | IllegalArgumentException | SecurityException ignored) {
            // Best-effort cleanup; Android/HAL still owns the final torch state.
        }
    }

    private void turnOffOurTorchBeforeLeaving() {
        clearPendingRequest();
        if (ownsTorchRequest && selectedCameraId != null) {
            safelyTurnOff(selectedCameraId);
            ownsTorchRequest = false;
        }
    }

    private void render() {
        boolean permissionGranted = hasCameraPermission();
        permissionInfo.setText(permissionGranted
                ? getString(R.string.permission_granted_explanation)
                : (permissionMessage == null ? getString(R.string.permission_needed_explanation) : permissionMessage));
        permissionButton.setText(permissionGranted
                ? R.string.scan_cameras_button
                : R.string.grant_camera_permission_button);
        permissionButton.setEnabled(true);
        rescanButton.setEnabled(permissionGranted);
        cameraSpinner.setEnabled(permissionGranted && frontFlashCameras.size() > 1);

        boolean canControl = permissionGranted && selectedCameraId != null;
        onButton.setEnabled(canControl && !torchUnavailable);
        offButton.setEnabled(canControl);

        if (!permissionGranted) {
            cameraInfo.setText(R.string.camera_info_waiting_for_permission);
            setBadge(R.string.status_permission_needed, R.color.badge_neutral_bg, R.color.badge_neutral_fg);
            statusDetail.setText(R.string.permission_needed_status);
            return;
        }

        if (frontFlashCameras.isEmpty() || selectedCameraId == null) {
            cameraInfo.setText(R.string.camera_info_no_front_flash);
            setBadge(R.string.status_no_front_flash, R.color.badge_warning_bg, R.color.badge_warning_fg);
            statusDetail.setText(scanMessage == null ? getString(R.string.scan_not_run) : scanMessage);
            return;
        }

        cameraInfo.setText(getString(R.string.camera_id_summary, selectedCameraId));
        if (operationError != null) {
            setBadge(R.string.status_request_failed, R.color.badge_error_bg, R.color.badge_error_fg);
            statusDetail.setText(operationError);
        } else if (torchUnavailable) {
            setBadge(R.string.status_torch_unavailable, R.color.badge_warning_bg, R.color.badge_warning_fg);
            statusDetail.setText(R.string.torch_unavailable_detail);
        } else if (requestPending) {
            setBadge(requestedTorchState ? R.string.status_requesting_on : R.string.status_requesting_off,
                    R.color.badge_neutral_bg, R.color.badge_neutral_fg);
            statusDetail.setText(R.string.waiting_for_hal);
        } else if (torchStateKnown && torchEnabled) {
            setBadge(R.string.status_torch_on, R.color.badge_on_bg, R.color.badge_on_fg);
            statusDetail.setText(R.string.torch_on_detail);
        } else if (torchStateKnown) {
            setBadge(R.string.status_torch_off, R.color.badge_neutral_bg, R.color.badge_neutral_fg);
            statusDetail.setText(R.string.torch_off_detail);
        } else {
            setBadge(R.string.status_ready, R.color.badge_neutral_bg, R.color.badge_neutral_fg);
            statusDetail.setText(scanMessage == null ? getString(R.string.tap_on_to_test) : scanMessage);
        }
    }

    private void setBadge(int textResource, int backgroundResource, int foregroundResource) {
        statusBadge.setText(textResource);
        statusBadge.setTextColor(getColor(foregroundResource));
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(backgroundResource));
        background.setCornerRadius(dp(40));
        statusBadge.setBackground(background);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? exception.getClass().getSimpleName() : message;
    }

    private static final class CameraTarget {
        final String id;

        CameraTarget(String id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return "Front camera  ·  ID " + id;
        }
    }
}
