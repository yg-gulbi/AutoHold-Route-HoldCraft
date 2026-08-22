# Climbing Hold Instance Segmentation

YOLO11 instance segmentation experiment for climbing holds.

## Dataset

The first training dataset was exported from Roboflow:

- Project/version: [hold_seg_data v1](https://app.roboflow.com/-jbsag/hold_seg_data/1)
- Export: `-seg-2z7dd-aw6dh-fork`, dated 2026-08-13
- License: CC BY 4.0
- Size: 7,520 images
- Preprocessing: auto-orientation and resize to 640x640 (stretch)
- Augmentation: none

The dataset files are not committed because the image/annotation export is approximately 945 MB and the COCO annotation file exceeds GitHub's 100 MB file limit. Download the export from Roboflow and place it at `hold_seg_dataset/` before training. The expected split directories are `train/`, `valid/`, and `test/`.

## Training

Install the Python dependencies used by the pipeline:

```powershell
pip install ultralytics opencv-python
```

From `01.climbing_instanceseg/`, run:

```powershell
python utiles_code/yolo_utiles.py
```

The current experiment uses:

- Model: `yolo11m-seg.pt`
- Epochs: 100
- Image size: 640
- Batch size: 16
- Device: GPU `0` (change to `cpu` if needed)
- Early stopping patience: 20
- Resume: disabled

Training outputs are written to `runs/yolo11_climbing_seg/`. The trained `best.pt` and `last.pt` weights are included when available; generated predictions and visualizations remain local.

## Classes

`climbing-hold`, `hold`, `volume`