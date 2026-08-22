# yolo11_climbing_seg_vr1.1

YOLO11m-seg를 사용한 등반 홀드 인스턴스 세그멘테이션 파인튜닝 실험입니다.

## 실험 정보

동일한 Roboflow 데이터셋 설정으로 `vr1.0` 모델을 파인튜닝했습니다.

- 초기 모델: `runs/yolo11_climbing_seg_vr1.0/train/weights/best.pt`
- 데이터 설정: `data_set/data.yaml`
- Epochs: 100
- 이미지 크기: 640
- Batch size: 16
- 장치: GPU `0`
- 조기 종료 대기: 20 epochs
- 이어 학습: 사용하지 않음

원본 이미지와 라벨은 이 실험 폴더에 복사하지 않았습니다. 실험에 사용한 데이터 설정과 Roboflow 메타데이터만 `data_set/`에 보관합니다.

## 데이터셋 정보

데이터셋은 Roboflow에서 YOLOv11 형식으로 다운로드했습니다.

- **출처**: [Roboflow Climbing Hold Detection v29](https://universe.roboflow.com/climbtag/climbing-hold-detection-7uehq/dataset/29)
- **전체 이미지**: 4,931장
- **클래스**: `hold`, `volume`
- **라벨 형식**: YOLOv11
- **기본 전처리**: EXIF 방향 정보 제거를 포함한 자동 방향 보정, 1024x1024 Fit 리사이즈(검은 여백 포함)
- **증강**: 원본 이미지마다 3개 버전 생성
	- 수평 반전 50% 확률
	- 0~30% 랜덤 크롭
	- -10~+10도 랜덤 회전
	- 가로·세로 방향 -3~+3도 랜덤 전단
	- 밝기 -15~+15% 조정
	- 노출 -10~+10% 조정
	- 픽셀의 0.1%에 Salt-and-pepper noise 적용

데이터 설정과 출처 정보는 다음 파일에서 확인할 수 있습니다.

- `data_set/data.yaml`
- `data_set/README.dataset.txt`
- `data_set/README.roboflow.txt`

## 검증 결과

| 지표 | 값 |
| --- | ---: |
| Box mAP50-95 | 0.7736 |
| Box mAP50 | 0.9202 |
| Mask mAP50-95 | 0.5840 |
| Mask mAP50 | 0.8652 |
| Precision | 0.9047 |
| Recall | 0.8751 |

전체 측정값은 `val/val_metrics.json`에 저장되어 있습니다.

## vr1.0과 성능 비교

| 지표 | vr1.0 | vr1.1 | 변화 |
| --- | ---: | ---: | ---: |
| Box mAP50-95 | 0.6976 | 0.7736 | +0.0760 |
| Box mAP50 | 0.8568 | 0.9202 | +0.0634 |
| Mask mAP50-95 | 0.5504 | 0.5840 | +0.0336 |
| Mask mAP50 | 0.8149 | 0.8652 | +0.0503 |
| Precision | 0.8834 | 0.9047 | +0.0213 |
| Recall | 0.8081 | 0.8751 | +0.0670 |

`vr1.1`은 `vr1.0`보다 모든 기록 지표가 향상되었습니다. 특히 Recall은 0.8081에서 0.8751로 0.0670 상승했습니다. 

## 산출물

- `train/weights/best.pt`: 성능이 가장 좋은 모델 가중치
- `train/weights/last.pt`: 마지막 학습 모델 가중치
- `train/results.csv`: 학습 과정 기록
- `train/`: 학습 그래프와 검증 미리보기 이미지
- `val/`: 검증 그래프와 측정값
- `predict/`: 추론 이미지와 결과 JSON

## 개인 이미지 테스트

`test.py`를 사용하면 학습이나 검증 없이 개인 이미지에 대해서만 추론할 수 있습니다.

```powershell
cd 01.climbing_instanceseg
python test.py
```

스크립트 실행 후 모델 경로와 개인 이미지 또는 이미지 폴더 경로를 입력합니다. 결과 이미지는 입력 이미지와 같은 폴더에 `_pred.jpg` 접미사를 붙여 저장됩니다.
