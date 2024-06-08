/*
 * Copyright (C) Photon Vision.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.photonvision.vision.camera;

import edu.wpi.first.cameraserver.CameraServer;
import edu.wpi.first.cscore.CvSink;
import edu.wpi.first.cscore.UsbCamera;
import edu.wpi.first.cscore.VideoException;
import edu.wpi.first.cscore.VideoMode;
import edu.wpi.first.cscore.VideoProperty.Kind;
import edu.wpi.first.util.PixelFormat;
import java.util.*;
import java.util.stream.Collectors;
import org.photonvision.common.configuration.CameraConfiguration;
import org.photonvision.common.configuration.ConfigManager;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.TestUtils;
import org.photonvision.vision.frame.FrameProvider;
import org.photonvision.vision.frame.provider.FileFrameProvider;
import org.photonvision.vision.frame.provider.USBFrameProvider;
import org.photonvision.vision.processes.VisionSource;
import org.photonvision.vision.processes.VisionSourceSettables;

public class USBCameraSource extends VisionSource {
    private final Logger logger;
    private final UsbCamera camera;
    private final USBCameraSettables usbCameraSettables;
    private FrameProvider usbFrameProvider;
    private final CvSink cvSink;

    public USBCameraSource(CameraConfiguration config) {
        super(config);

        logger = new Logger(USBCameraSource.class, config.nickname, LogGroup.Camera);
        // cscore will auto-reconnect to the camera path we give it. v4l does not
        // guarantee that if i
        // swap cameras around, the same /dev/videoN ID will be assigned to that camera.
        // So instead
        // default to pinning to a particular USB port, or by "path" (appears to be a
        // global identifier)
        // on Windows.
        camera = new UsbCamera(config.nickname, config.getUSBPath().orElse(config.path));
        cvSink = CameraServer.getVideo(this.camera);

        // set vid/pid if not done already for future matching
        if (config.usbVID <= 0) config.usbVID = this.camera.getInfo().vendorId;
        if (config.usbPID <= 0) config.usbPID = this.camera.getInfo().productId;

        if (getCameraConfiguration().cameraQuirks == null)
            getCameraConfiguration().cameraQuirks =
                    QuirkyCamera.getQuirkyCamera(
                            camera.getInfo().vendorId, camera.getInfo().productId, config.baseName);

        if (getCameraConfiguration().cameraQuirks.hasQuirks()) {
            logger.info("Quirky camera detected: " + getCameraConfiguration().cameraQuirks.baseName);
        }

        if (getCameraConfiguration().cameraQuirks.hasQuirk(CameraQuirk.CompletelyBroken)) {
            // set some defaults, as these should never be used.
            logger.info(
                    "Camera "
                            + getCameraConfiguration().cameraQuirks.baseName
                            + " is not supported for PhotonVision");
            usbCameraSettables = null;
            usbFrameProvider = null;
        } else {
            // Normal init
            // auto exposure/brightness/gain will be set by the visionmodule later
            disableAutoFocus();

            usbCameraSettables = new USBCameraSettables(config);
            if (usbCameraSettables.getAllVideoModes().isEmpty()) {
                logger.info("Camera " + camera.getPath() + " has no video modes supported by PhotonVision");
                usbFrameProvider = null;
            } else {
                usbFrameProvider = new USBFrameProvider(cvSink, usbCameraSettables);
            }
        }
    }

    /**
     * Mostly just used for unit tests to better simulate a usb camera without a camera being present.
     */
    public USBCameraSource(CameraConfiguration config, int pid, int vid, boolean unitTest) {
        this(config);

        if (getCameraConfiguration().cameraQuirks == null)
            getCameraConfiguration().cameraQuirks =
                    QuirkyCamera.getQuirkyCamera(pid, vid, config.baseName);

        if (unitTest)
            usbFrameProvider =
                    new FileFrameProvider(
                            TestUtils.getWPIImagePath(
                                    TestUtils.WPI2019Image.kCargoStraightDark72in_HighRes, false),
                            TestUtils.WPI2019Image.FOV);
    }

    void disableAutoFocus() {
        if (getCameraConfiguration().cameraQuirks.hasQuirk(CameraQuirk.AdjustableFocus)) {
            try {
                camera.getProperty("focus_auto").set(0);
                camera.getProperty("focus_absolute").set(0); // Focus into infinity
            } catch (VideoException e) {
                logger.error("Unable to disable autofocus!", e);
            }
        }
    }

    public QuirkyCamera getCameraQuirks() {
        return getCameraConfiguration().cameraQuirks;
    }

    @Override
    public FrameProvider getFrameProvider() {
        return usbFrameProvider;
    }

    @Override
    public VisionSourceSettables getSettables() {
        return this.usbCameraSettables;
    }

    public class USBCameraSettables extends VisionSourceSettables {
        // We need to remember the last exposure set when exiting auto exposure mode so
        // we can restore
        // it
        private int last_exposure = 20;
        private int last_brightness = 50;

        protected USBCameraSettables(CameraConfiguration configuration) {
            super(configuration);
            getAllVideoModes();
            if (!configuration.cameraQuirks.hasQuirk(CameraQuirk.StickyFPS))
                if (!videoModes.isEmpty()) setVideoMode(videoModes.get(0)); // fixes double FPS set
        }

        public void setAutoExposure(boolean cameraAutoExposure) {
            logger.debug("Setting auto exposure to " + cameraAutoExposure);

            // Case - this is some other USB cam. Default to wpilib's implementation

            var canSetWhiteBalance = !getCameraConfiguration().cameraQuirks.hasQuirk(CameraQuirk.Gain);

            if (!cameraAutoExposure) {
                // Pick a bunch of reasonable setting defaults for vision processing
                // retroreflective
                if (canSetWhiteBalance) {
                    // Linux kernel bump changed names -- now called white_balance_automatic and
                    // white_balance_temperature
                    if (camera.getProperty("white_balance_automatic").getKind() != Kind.kNone) {
                        // 1=auto, 0=manual
                        camera.getProperty("white_balance_automatic").set(0);
                        camera.getProperty("white_balance_temperature").set(4000);
                    } else {
                        camera.setWhiteBalanceManual(4000); // Auto white-balance disabled, 4000K preset
                    }

                    // Most cameras leave exposure time absolute at the last value from their AE
                    // algorithm.
                    // Set it back to the exposure slider value
                    setExposure(this.last_exposure);
                }
            } else {
                // Pick a bunch of reasonable setting defaults for driver, fiducials, or
                // otherwise
                // nice-for-humans
                if (canSetWhiteBalance) {
                    // Linux kernel bump changed names -- now called white_balance_automatic
                    if (camera.getProperty("white_balance_automatic").getKind() != Kind.kNone) {
                        // 1=auto, 0=manual
                        camera.getProperty("white_balance_automatic").set(1);
                    } else {
                        camera.setWhiteBalanceAuto(); // Auto white-balance enabled
                    }
                }

                // Linux kernel bump changed names -- exposure_auto is now called auto_exposure
                if (camera.getProperty("auto_exposure").getKind() != Kind.kNone) {
                    var prop = camera.getProperty("auto_exposure");
                    // 3=auto-aperature
                    prop.set((int) 3);
                } else {
                    camera.setExposureAuto(); // auto exposure enabled
                }
            }
        }

        @Override
        public void setExposure(int exposure) {
            if (exposure >= 0.0) {
                try {
                    logger.debug("Setting camera exposure to " + exposure);
                    if (camera.getProperty("exposure_time_absolute").getKind() != Kind.kNone
                            && camera.getProperty("auto_exposure").getKind() != Kind.kNone) {
                        // 1=manual-aperature
                        camera.getProperty("auto_exposure").set(1);

                        camera.getProperty("raw_exposure_time_absolute").set((int) exposure);
                    } else {
                        camera.setExposureManual(exposure);
                    }
                } catch (VideoException e) {
                    logger.error("Failed to set camera exposure!", e);
                }
                this.last_exposure = exposure;
            }
        }

        @Override
        public int getMinExposure() {
            if (getCameraConfiguration().cameraQuirks.hasQuirk(CameraQuirk.ArduOV9281)) return 1;
            if (getCameraConfiguration().cameraQuirks.hasQuirk(CameraQuirk.ArduOV2311)) return 1;
            if (camera.getProperty("auto_exposure").getKind() != Kind.kNone)
                return camera.getProperty("raw_exposure_time_absolute").getMin();
            return 1;
        }

        @Override
        public int getMaxExposure() {
            if (getCameraConfiguration().cameraQuirks.hasQuirk(CameraQuirk.ArduOV9281)) return 75;
            if (getCameraConfiguration().cameraQuirks.hasQuirk(CameraQuirk.ArduOV2311)) return 140;
            if (camera.getProperty("auto_exposure").getKind() != Kind.kNone)
                return camera.getProperty("raw_exposure_time_absolute").getMax();
            return 100;
        }

        @Override
        public void setBrightness(int brightness) {
            try {
                logger.debug("Setting camera brightness to " + brightness);
                camera.setBrightness(brightness);
                camera.setBrightness(brightness);

            } catch (VideoException e) {
                logger.error("Failed to set camera brightness!", e);
            }
            this.last_brightness = brightness;
        }

        @Override
        public int getMinBrightness() {
            return 0;
        }

        @Override
        public int getMaxBrightness() {
            return 100;
        }

        @Override
        public void setGain(int gain) {
            try {
                if (getCameraConfiguration().cameraQuirks.hasQuirk(CameraQuirk.Gain)) {
                    camera.getProperty("gain_automatic").set(0);
                    camera.getProperty("gain").set(gain);
                }
            } catch (VideoException e) {
                logger.error("Failed to set camera gain!", e);
            }
        }

        @Override
        public int getMinGain() {
            if (getCameraConfiguration().cameraQuirks.hasQuirk(CameraQuirk.Gain))
                return camera.getProperty("gain").getMin();
            return 0;
        }

        @Override
        public int getMaxGain() {
            if (getCameraConfiguration().cameraQuirks.hasQuirk(CameraQuirk.Gain))
                return camera.getProperty("gain").getMax();
            return 100;
        }

        @Override
        public VideoMode getCurrentVideoMode() {
            return camera.isConnected() ? camera.getVideoMode() : null;
        }

        @Override
        public void setVideoModeInternal(VideoMode videoMode) {
            try {
                if (videoMode == null) {
                    logger.error("Got a null video mode! Doing nothing...");
                    return;
                }
                camera.setVideoMode(videoMode);
            } catch (Exception e) {
                logger.error("Failed to set video mode!", e);
            }
        }

        @Override
        public HashMap<Integer, VideoMode> getAllVideoModes() {
            if (videoModes == null) {
                videoModes = new HashMap<>();
                List<VideoMode> videoModesList = new ArrayList<>();
                try {
                    VideoMode[] modes;
                    modes = camera.enumerateVideoModes();
                    for (VideoMode videoMode : modes) {
                        // Filter grey modes
                        if (videoMode.pixelFormat == PixelFormat.kGray
                                || videoMode.pixelFormat == PixelFormat.kUnknown) {
                            continue;
                        }

                        if (getCameraConfiguration().cameraQuirks.hasQuirk(CameraQuirk.FPSCap100)) {
                            if (videoMode.fps > 100) {
                                continue;
                            }
                        }

                        videoModesList.add(videoMode);

                        // TODO - do we want to trim down FPS modes? in cases where the camera has no
                        // gain
                        // control,
                        // lower FPS might be needed to ensure total exposure is acceptable.
                        // We look for modes with the same height/width/pixelformat as this mode
                        // and remove all the ones that are slower. This is sorted low to high.
                        // So we remove the last element (the fastest FPS) from the duplicate list,
                        // and remove all remaining elements from the final list
                        // var duplicateModes =
                        // videoModesList.stream()
                        // .filter(
                        // it ->
                        // it.height == videoMode.height
                        // && it.width == videoMode.width
                        // && it.pixelFormat == videoMode.pixelFormat)
                        // .sorted(Comparator.comparingDouble(it -> it.fps))
                        // .collect(Collectors.toList());
                        // duplicateModes.remove(duplicateModes.size() - 1);
                        // videoModesList.removeAll(duplicateModes);
                    }
                } catch (Exception e) {
                    logger.error("Exception while enumerating video modes!", e);
                    videoModesList = List.of();
                }

                // Sort by resolution
                var sortedList =
                        videoModesList.stream()
                                .distinct() // remove redundant video mode entries
                                .sorted(((a, b) -> (b.width + b.height) - (a.width + a.height)))
                                .collect(Collectors.toList());
                Collections.reverse(sortedList);

                // On vendor cameras, respect blacklisted indices
                var indexBlacklist =
                        ConfigManager.getInstance().getConfig().getHardwareConfig().blacklistedResIndices;
                for (int badIdx : indexBlacklist) {
                    sortedList.remove(badIdx);
                }

                for (VideoMode videoMode : sortedList) {
                    videoModes.put(sortedList.indexOf(videoMode), videoMode);
                }
            }
            return videoModes;
        }
    }

    // TODO improve robustness of this detection
    @Override
    public boolean isVendorCamera() {
        return ConfigManager.getInstance().getConfig().getHardwareConfig().hasPresetFOV();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        USBCameraSource other = (USBCameraSource) obj;
        if (camera == null) {
            if (other.camera != null) return false;
        } else if (!camera.equals(other.camera)) return false;
        if (usbCameraSettables == null) {
            if (other.usbCameraSettables != null) return false;
        } else if (!usbCameraSettables.equals(other.usbCameraSettables)) return false;
        if (usbFrameProvider == null) {
            if (other.usbFrameProvider != null) return false;
        } else if (!usbFrameProvider.equals(other.usbFrameProvider)) return false;
        if (cvSink == null) {
            if (other.cvSink != null) return false;
        } else if (!cvSink.equals(other.cvSink)) return false;
        if (getCameraConfiguration().cameraQuirks == null) {
            if (other.getCameraConfiguration().cameraQuirks != null) return false;
        } else if (!getCameraConfiguration()
                .cameraQuirks
                .equals(other.getCameraConfiguration().cameraQuirks)) return false;
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                camera,
                usbCameraSettables,
                usbFrameProvider,
                cameraConfiguration,
                cvSink,
                getCameraConfiguration().cameraQuirks);
    }
}
