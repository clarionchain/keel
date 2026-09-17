/** QR generation (qrcode lib) and camera scanning (getUserMedia + jsQR). */
import QRCode from "qrcode";
import jsQR from "jsqr";

export async function qrCanvas(text: string, size = 220): Promise<HTMLCanvasElement> {
  const canvas = document.createElement("canvas");
  await QRCode.toCanvas(canvas, text, { width: size, margin: 0 });
  canvas.className = "qr";
  return canvas;
}

export interface ScanHandle {
  stop: () => void;
}

/** Starts the camera and calls onResult once when a QR decodes. */
export async function startScan(
  video: HTMLVideoElement,
  onResult: (text: string) => void,
  onError: (msg: string) => void,
): Promise<ScanHandle> {
  let stream: MediaStream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: "environment" },
      audio: false,
    });
  } catch {
    onError("Camera not available or permission denied");
    return { stop: () => {} };
  }
  video.srcObject = stream;
  await video.play();

  const canvas = document.createElement("canvas");
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  let stopped = false;
  let raf = 0;

  const tick = () => {
    if (stopped) return;
    if (video.readyState === video.HAVE_ENOUGH_DATA && ctx) {
      canvas.width = video.videoWidth;
      canvas.height = video.videoHeight;
      ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
      const frame = ctx.getImageData(0, 0, canvas.width, canvas.height);
      const code = jsQR(frame.data, frame.width, frame.height, { inversionAttempts: "dontInvert" });
      if (code && code.data) {
        stop();
        onResult(code.data);
        return;
      }
    }
    raf = requestAnimationFrame(tick);
  };

  const stop = () => {
    stopped = true;
    cancelAnimationFrame(raf);
    stream.getTracks().forEach((t) => t.stop());
    video.srcObject = null;
  };

  raf = requestAnimationFrame(tick);
  return { stop };
}
