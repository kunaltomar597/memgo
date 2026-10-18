"""SCRFD face detection: boxes, scores and 5 landmarks per face.

A direct port of the SCRFD post-processing used by InsightFace, so the hub
needs only ONNX Runtime (no compiled `insightface` package on the Pi).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import cv2
import numpy as np
from numpy.typing import NDArray

INPUT_SIZE = 640
STRIDES = (8, 16, 32)
NUM_ANCHORS = 2
NMS_THRESHOLD = 0.4

FloatArray = NDArray[np.float32]


@dataclass(frozen=True)
class Detection:
    box: tuple[float, float, float, float]  # x1, y1, x2, y2 in frame pixels
    score: float
    landmarks: FloatArray  # (5, 2): left eye, right eye, nose, left mouth, right mouth

    @property
    def width(self) -> float:
        return self.box[2] - self.box[0]

    @property
    def height(self) -> float:
        return self.box[3] - self.box[1]


def _distance2bbox(points: FloatArray, distance: FloatArray) -> FloatArray:
    x1 = points[:, 0] - distance[:, 0]
    y1 = points[:, 1] - distance[:, 1]
    x2 = points[:, 0] + distance[:, 2]
    y2 = points[:, 1] + distance[:, 3]
    out: FloatArray = np.stack([x1, y1, x2, y2], axis=-1).astype(np.float32)
    return out


def _distance2kps(points: FloatArray, distance: FloatArray) -> FloatArray:
    out = []
    for i in range(0, distance.shape[1], 2):
        out.append(points[:, 0] + distance[:, i])
        out.append(points[:, 1] + distance[:, i + 1])
    return np.stack(out, axis=-1).astype(np.float32)


def _nms(dets: FloatArray, threshold: float) -> list[int]:
    x1, y1, x2, y2, scores = dets[:, 0], dets[:, 1], dets[:, 2], dets[:, 3], dets[:, 4]
    areas = (x2 - x1 + 1) * (y2 - y1 + 1)
    order = scores.argsort()[::-1]
    keep: list[int] = []
    while order.size > 0:
        i = int(order[0])
        keep.append(i)
        xx1 = np.maximum(x1[i], x1[order[1:]])
        yy1 = np.maximum(y1[i], y1[order[1:]])
        xx2 = np.minimum(x2[i], x2[order[1:]])
        yy2 = np.minimum(y2[i], y2[order[1:]])
        w = np.maximum(0.0, xx2 - xx1 + 1)
        h = np.maximum(0.0, yy2 - yy1 + 1)
        inter = w * h
        ovr = inter / (areas[i] + areas[order[1:]] - inter)
        order = order[np.where(ovr <= threshold)[0] + 1]
    return keep


_centers: dict[tuple[int, int, int], FloatArray] = {}


def _anchor_centers(height: int, width: int, stride: int) -> FloatArray:
    key = (height, width, stride)
    if key not in _centers:
        ys, xs = np.mgrid[:height, :width]
        grid = np.stack([xs, ys], axis=-1).astype(np.float32)
        centers = (grid * stride).reshape((-1, 2))
        centers = np.stack([centers] * NUM_ANCHORS, axis=1).reshape((-1, 2))
        _centers[key] = centers.astype(np.float32)
    return _centers[key]


def detect(
    session: Any,
    image: NDArray[np.uint8],
    *,
    score_threshold: float = 0.5,
    min_face_px: int = 40,
    max_faces: int = 0,
) -> list[Detection]:
    """Detect faces in a BGR image. Faces narrower than `min_face_px` are dropped."""
    h, w = image.shape[:2]
    if h / w > 1.0:
        new_h = INPUT_SIZE
        new_w = int(new_h * w / h)
    else:
        new_w = INPUT_SIZE
        new_h = int(new_w * h / w)
    scale = new_h / h
    canvas = np.zeros((INPUT_SIZE, INPUT_SIZE, 3), dtype=np.uint8)
    canvas[:new_h, :new_w] = cv2.resize(image, (new_w, new_h))
    blob = cv2.dnn.blobFromImage(
        canvas, 1.0 / 128.0, (INPUT_SIZE, INPUT_SIZE), (127.5, 127.5, 127.5), swapRB=True
    )
    input_name = session.get_inputs()[0].name
    outs = session.run(None, {input_name: blob})
    fmc = len(STRIDES)
    all_scores, all_boxes, all_kps = [], [], []
    for idx, stride in enumerate(STRIDES):
        scores = outs[idx].reshape(-1)
        boxes = outs[idx + fmc].reshape(-1, 4) * stride
        kps = outs[idx + fmc * 2].reshape(-1, 10) * stride
        fh, fw = INPUT_SIZE // stride, INPUT_SIZE // stride
        centers = _anchor_centers(fh, fw, stride)
        pos = np.where(scores >= score_threshold)[0]
        if pos.size == 0:
            continue
        all_scores.append(scores[pos])
        all_boxes.append(_distance2bbox(centers, boxes)[pos])
        all_kps.append(_distance2kps(centers, kps)[pos])
    if not all_scores:
        return []
    scores_np = np.concatenate(all_scores).astype(np.float32)
    boxes_np = np.concatenate(all_boxes) / scale
    kps_np = (np.concatenate(all_kps) / scale).reshape(-1, 5, 2)
    order = scores_np.argsort()[::-1]
    dets = np.hstack([boxes_np, scores_np[:, None]]).astype(np.float32)[order]
    kps_np = kps_np[order]
    keep = _nms(dets, NMS_THRESHOLD)
    result: list[Detection] = []
    for i in keep:
        x1, y1, x2, y2, s = (float(v) for v in dets[i])
        if x2 - x1 < min_face_px:
            continue
        result.append(Detection((x1, y1, x2, y2), s, kps_np[i].astype(np.float32)))
    result.sort(key=lambda d: d.width * d.height, reverse=True)
    return result[:max_faces] if max_faces > 0 else result
