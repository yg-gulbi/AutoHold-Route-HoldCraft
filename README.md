# AutoHold Route HoldCraft

클라이밍 암장 이미지에서 `hold`와 `volume`을 찾아내고, 각 객체의 바운딩 박스와 세그멘테이션 마스크를 추출하는 YOLO11 기반 인스턴스 세그멘테이션 실험 저장소입니다.

## 프로젝트 개요

이 프로젝트는 볼더링 벽 사진을 입력으로 받아 클라이밍 홀드와 볼륨을 검출하는 모델을 학습하고 검증합니다. 핵심 모델은 `YOLO11m-seg`이며, 학습부터 검증, 이미지 추론, 결과 JSON 저장까지 하나의 파이프라인으로 구성되어 있습니다.

- 모델 유형: YOLO11 instance segmentation
- 검출 클래스: `hold`, `volume`
- 주요 작업: 학습, 검증, 예측 이미지 생성, 객체별 좌표와 마스크 폴리곤 저장
- 최신 실험: `01.climbing_instanceseg/runs/yolo11_climbing_seg_vr_1.1`

## 폴더 구조

```text
.
├── 01.climbing_instanceseg/
│   ├── README.md
│   ├── yolo_utiles.py
│   ├── test.py
│   ├── dataset/
│   ├── test/
│   ├── notebooks/archive/
│   └── runs/
│       ├── yolo11_climbing_seg_vr_1.0/
│       └── yolo11_climbing_seg_vr_1.1/
└── .gitignore
```

- `yolo_utiles.py`: 학습, 검증, 추론을 처리하는 메인 파이프라인입니다.
- `test.py`: 지정된 모델과 이미지로 빠르게 추론 결과 이미지를 생성하는 테스트 스크립트입니다.
- `dataset/`: `yolo_utiles.ipynb`와 `yolo_utiles.py`가 실제 학습 때 읽는 데이터셋 위치입니다. 이 폴더는 로컬 준비 대상이며 Git에는 포함하지 않습니다.
- `runs/`: 실험별 학습 결과, 검증 지표, 예측 결과, 모델 가중치가 저장됩니다.
- `runs/yolo11_climbing_seg_vr_1.1/data_info/`: 최신 실험에 남겨진 데이터셋 설정과 Roboflow 메타데이터 기록입니다. 학습 입력 원본 위치는 아닙니다.
- `test/`: 개인 이미지 추론 테스트용 샘플과 예측 결과가 들어 있습니다.

## 데이터셋

최신 실험(`vr_1.1`)은 Roboflow의 `Climbing Hold Detection` v29 데이터셋을 기준으로 합니다.

- 출처: `climbtag/climbing-hold-detection-7uehq`, version 29
- 라이선스: CC BY 4.0
- 전체 이미지 수: 4,931장
- 클래스 수: 2개
- 클래스 이름: `hold`, `volume`
- 형식: YOLOv11 segmentation

`yolo_utiles.ipynb`는 `yolo_utiles.py`에서 `DATASET_DIR`와 `DATA_YAML`을 import해 사용합니다. 현재 코드 기준 실제 학습 데이터셋은 아래 위치에 있어야 합니다.

```text
01.climbing_instanceseg/dataset/data.yaml
```

Roboflow 다운로드 셀도 `location=str(DATASET_DIR)`로 되어 있으므로, 데이터셋을 다시 내려받을 때도 `01.climbing_instanceseg/dataset/` 아래에 이미지, 라벨, `data.yaml`이 생성되는 구조가 맞습니다. `runs/` 아래의 데이터 관련 폴더는 실험 당시의 설정 파일과 README를 남겨두는 기록용 복사본입니다.

## 최신 실험 요약

`yolo11_climbing_seg_vr_1.1`은 `vr_1.0`에서 학습된 `best.pt`를 초기 모델로 사용해 동일 계열 데이터셋으로 파인튜닝한 실험입니다.

| 항목 | 값 |
| --- | --- |
| 초기 모델 | `runs/yolo11_climbing_seg_vr_1.0/train/weights/best.pt` |
| Epochs | 100 |
| 이미지 크기 | 640 |
| Batch size | 16 |
| Device | GPU `0` |
| Early stopping patience | 20 |
| Resume | 사용 안 함 |

## 성능 결과

| 지표 | vr_1.0 | vr_1.1 | 변화 |
| --- | ---: | ---: | ---: |
| Box mAP50-95 | 0.6976 | 0.7736 | +0.0760 |
| Box mAP50 | 0.8568 | 0.9202 | +0.0634 |
| Mask mAP50-95 | 0.5504 | 0.5840 | +0.0336 |
| Mask mAP50 | 0.8149 | 0.8652 | +0.0503 |
| Precision | 0.8834 | 0.9047 | +0.0213 |
| Recall | 0.8081 | 0.8751 | +0.0670 |

`vr_1.1`은 `vr_1.0`보다 모든 주요 지표가 개선되었습니다. 특히 Recall이 0.8081에서 0.8751로 올라가 홀드와 볼륨을 놓치는 비율이 줄었습니다.

## 설치

```powershell
cd 01.climbing_instanceseg
pip install ultralytics opencv-python
```

GPU를 사용하려면 현재 환경의 CUDA와 PyTorch 설치 상태를 먼저 확인하세요. GPU가 없거나 CUDA 설정이 맞지 않으면 스크립트에서 `device` 값을 `cpu`로 바꾸면 됩니다.

## 학습, 검증, 추론 실행

`01.climbing_instanceseg/` 폴더에서 메인 파이프라인을 실행합니다.

```powershell
python yolo_utiles.py
```

스크립트와 노트북은 학습 데이터로 기본적으로 `01.climbing_instanceseg/dataset/data.yaml`을 사용하며, 결과는 `runs/` 아래의 `exp_name`으로 지정된 실험 폴더에 저장됩니다.

실험명을 새로 관리하려면 `yolo_utiles.py`의 `YOLOSegmentPipeline(..., exp_name=...)` 값을 변경하세요.

## 개인 이미지 빠른 테스트

`test.py`는 학습이나 검증 없이 지정된 이미지에 대해서만 예측 이미지를 생성합니다.

```powershell
cd 01.climbing_instanceseg
python test.py
```

현재 `test.py`의 `model_path`와 `source_path`는 로컬 절대 경로로 하드코딩되어 있습니다. 다른 모델이나 이미지를 사용하려면 해당 값을 먼저 수정하세요. 결과 이미지는 입력 이미지와 같은 폴더에 `_pred_...jpg` 형식으로 저장됩니다.

## 주요 산출물

- `train/weights/best.pt`: 검증 성능이 가장 좋은 모델 가중치
- `train/weights/last.pt`: 마지막 epoch 기준 모델 가중치
- `train/results.csv`: 학습 과정별 지표 기록
- `train/results.png`: 학습 지표 그래프
- `val/val_metrics.json`: 검증 성능 요약
- `predict/inference_results.json`: 이미지별 검출 객체, confidence, bounding box, polygon 정보
- `predict/*_pred.jpg`: 예측 결과가 시각화된 이미지

## 참고 사항

- `dataset/` 폴더와 `01.climbing_instanceseg/yolo11m-seg.pt`는 `.gitignore`에 포함되어 있어 로컬에서 별도로 준비해야 합니다.
- 실험별 README와 세부 결과는 `01.climbing_instanceseg/runs/` 아래 각 실험 폴더에서 확인할 수 있습니다.
- `vr_1.1` 기준 검증 지표는 `01.climbing_instanceseg/runs/yolo11_climbing_seg_vr_1.1/val/val_metrics.json`에 저장되어 있습니다.
