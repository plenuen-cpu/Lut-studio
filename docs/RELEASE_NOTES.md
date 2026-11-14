## FilmCam 1.0

Ricoh GR 커스텀 펌웨어(GRLife) 스타일의 실시간 LUT 필름 카메라.

### 룩 57종
- Kodak 24 · Fujifilm 14 · Agfa 7 · Ilford 1 (필름 46종)
- Leica 6 · Ricoh GR 5 (카메라 룩 11종)
- Tonefinder 2.0.1 내장 3D LUT(17×17×17)을 그대로 추출해 같은 색감으로 적용. VSCO·폰 브랜드 룩은 제외.

### 기능
- CameraX + GLES3 실시간 프리뷰 (17³ 3D LUT 트라이리니어 보간, 강도 0~100% mix)
- 'Films in use' 필름스트립: 퍼포레이션 프레임, 노란 포커스 테두리, 번호+이름 라벨
- 필름 캐니스터 아트(코닥/포트라/후지/아그파/일포드) + Leica·Ricoh GR 브랜드 배지
- 브랜드 필터 칩, LUT 강도 슬라이더, 전/후면 전환, 3:2·4:3·16:9·1:1
- Pictures/FilmCam 저장, 최근 사진 썸네일·전체화면 보기, 촬영 후 "현상 중…" 처리

### 설치
`FilmCam-debug.apk`를 내려받아 설치하세요. 이전에 설치한 FilmCam이 있으면 **삭제 후 설치**가 필요합니다
(CI 빌드마다 디버그 서명 키가 새로 만들어집니다).

### 검증
단위 테스트 23개 통과 (Gradle 8.9 / JDK 17 / compileSdk 35):
- 57종 LUT 디코드가 원본 Tonefinder와 sha256·샘플 노드까지 일치
- 브랜드별 개수(코닥 24·후지 14·아그파 7·일포드 1·라이카 6·리코 GR 5)와 슬롯 1~57 매핑
- B&W 룩의 회색 중립성, 흑→백 단조성, 트라이리니어 보간, 강도 0/50/100% 혼합
- 프리뷰 회전(90/180/270)·미러·종횡비 크롭 변환 행렬

APK: `apksigner verify` 통과, `com.plenuen.filmcam`, minSdk 26 / targetSdk 35.
