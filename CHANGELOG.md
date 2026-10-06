# Changelog

## v1.5.3 - 2026-10-06

### [1.5.3](https://github.com/wenisch-tech/s3webui/compare/v1.5.2...v1.5.3) (2026-10-06)


### Documentation

* updated readme / preview ([015009a](https://github.com/wenisch-tech/s3webui/commit/015009a1d60b119cdb69959b9cde65a32ef2d199))



Docker image: ghcr.io/wenisch-tech/s3webui:1.5.3


## v1.5.2 - 2026-10-06

### [1.5.2](https://github.com/wenisch-tech/s3webui/compare/v1.5.1...v1.5.2) (2026-10-06)



Docker image: ghcr.io/wenisch-tech/s3webui:1.5.2


## v1.5.0 - 2026-10-06

## [1.5.0](https://github.com/wenisch-tech/s3webui/compare/v1.4.1...v1.5.0) (2026-10-06)


### Features

*  ([#50](https://github.com/wenisch-tech/s3webui/issues/50)) Addes search in buckets including virtual paths ([e0757cf](https://github.com/wenisch-tech/s3webui/commit/e0757cf2dc03dff02656b9b0a50b7459a813f39b))
*  updated display of cards ([1a0f880](https://github.com/wenisch-tech/s3webui/commit/1a0f880aeb67f41cedb12579d6d911d568743c18))
* added global search ([d1d3573](https://github.com/wenisch-tech/s3webui/commit/d1d3573ea080cc0ace800880c59974cd40454df8))
* Added IAM Support for RustFS ([f6259bc](https://github.com/wenisch-tech/s3webui/commit/f6259bcd45174c854c696aa265701f34eb7097d4))
* added sorting support for bucket listings ( ([#51](https://github.com/wenisch-tech/s3webui/issues/51)) ([6863c37](https://github.com/wenisch-tech/s3webui/commit/6863c373f4e1186ef836974e21ba307be58b2f60))
* initial commit implementing search (only in current bucket / view ( ([#50](https://github.com/wenisch-tech/s3webui/issues/50)) ([747a11f](https://github.com/wenisch-tech/s3webui/commit/747a11f89121c4a3f01eb01edf093d21cb9e4841))


### Bug Fixes

* Ensure all files are properly loaded when having more than 1000 files ([#49](https://github.com/wenisch-tech/s3webui/issues/49)) and implemented pagination ([320d9ce](https://github.com/wenisch-tech/s3webui/commit/320d9ce6226f10c37228025c482f2c5cc766d3c7))
* fixed identification of rustFS Endpoint ([90922b5](https://github.com/wenisch-tech/s3webui/commit/90922b561c02fdf17fe263545b7b674fdc7224a4))
* minor changes to fonts and overall design ([e396f65](https://github.com/wenisch-tech/s3webui/commit/e396f658a409677f5fb070abe01a4a62ee213bff))
* re-ordered top-menu ([e4dede7](https://github.com/wenisch-tech/s3webui/commit/e4dede7a47a13adce3ffb20cc1fe4cea314a5b6e))
* updated GUI Tests regarding used fonts ([f5649f4](https://github.com/wenisch-tech/s3webui/commit/f5649f46d1def9aafd98b0da17847e0de97533b5))
* updated tests for rustfs. ([3fb1e20](https://github.com/wenisch-tech/s3webui/commit/3fb1e20df7f6407b739fca54d180d9ec70d28653))


### Documentation

* updated Readme and replaced minio example with rustfs ([9621aa4](https://github.com/wenisch-tech/s3webui/commit/9621aa4b981a604a943bec79e9522b1d14de13d5))



Docker image: ghcr.io/wenisch-tech/s3webui:1.5.0


## v1.4.1 - 2026-10-05

### [1.4.1](https://github.com/wenisch-tech/s3webui/compare/v1.4.0...v1.4.1) (2026-10-05)



Docker image: ghcr.io/wenisch-tech/s3webui:1.4.1


## v1.4.0 - 2026-10-05

## [1.4.0](https://github.com/wenisch-tech/s3webui/compare/v1.3.4...v1.4.0) (2026-10-05)


### Features

* **helm:** support existingSecrets to keep secrets out of values.yaml ([8f827a3](https://github.com/wenisch-tech/s3webui/commit/8f827a309465271fedb171bdf1c86ac9b7f804ea))


### Bug Fixes

* **helm:** harden existing secret support ([79ab452](https://github.com/wenisch-tech/s3webui/commit/79ab452ae6d8be507e3f41577d04202d1b94e9bd))


### Styles

* **values:** shorten existingSecrets and secrets comments ([89dde19](https://github.com/wenisch-tech/s3webui/commit/89dde19d621cf566b3c17b92a98c9a1a347db0b4))



Docker image: ghcr.io/wenisch-tech/s3webui:1.4.0


## v1.3.4 - 2026-09-27

### [1.3.4](https://github.com/wenisch-tech/s3webui/compare/v1.3.3...v1.3.4) (2026-09-27)


### Bug Fixes

* **deps:** update aws-java-sdk-v2 monorepo to v2.55.6 ([b388bc8](https://github.com/wenisch-tech/s3webui/commit/b388bc8231f71628aeca6bcbdc6dfdf8c219ff70))



Docker image: ghcr.io/wenisch-tech/s3webui:1.3.4


## v1.3.3 - 2026-09-24

### [1.3.3](https://github.com/wenisch-tech/s3webui/compare/v1.3.2...v1.3.3) (2026-09-24)


### Bug Fixes

* **deps:** update dependency alpinejs to v3.17.4 ([5ef4fcf](https://github.com/wenisch-tech/s3webui/commit/5ef4fcf552a58b8426d5e6cf856d78a15595fcf3))



Docker image: ghcr.io/wenisch-tech/s3webui:1.3.3


## v1.3.2 - 2026-09-24

### [1.3.2](https://github.com/wenisch-tech/s3webui/compare/v1.3.1...v1.3.2) (2026-09-24)


### Bug Fixes

* support arbitrary servlet context paths ([edf2022](https://github.com/wenisch-tech/s3webui/commit/edf202265c6c4642f778250cf93d05e51f652639))



Docker image: ghcr.io/wenisch-tech/s3webui:1.3.2


## v1.3.1 - 2026-09-22

### [1.3.1](https://github.com/wenisch-tech/s3webui/compare/v1.3.0...v1.3.1) (2026-09-22)


### Bug Fixes

* **deps:** update non-major dependencies ([3231f44](https://github.com/wenisch-tech/s3webui/commit/3231f44eaad4d450fb7708cf197e50b4425b612d))



Docker image: ghcr.io/wenisch-tech/s3webui:1.3.1


## v1.3.0 - 2026-09-22

## [1.3.0](https://github.com/wenisch-tech/s3webui/compare/v1.2.3...v1.3.0) (2026-09-22)


### Features

*  option flag to reveal secrets ([da07e68](https://github.com/wenisch-tech/s3webui/commit/da07e68124d8f08be98d77626dcbf2b176b75ece))


### Bug Fixes

* fixed user policy handling with ceph ([cb26752](https://github.com/wenisch-tech/s3webui/commit/cb2675287dc30cdc06d57de2edae0932c2029891))
* re-added authenticationless mode allowing usage of s3webui without user credentials (besides s3 creds) ([e24b4d6](https://github.com/wenisch-tech/s3webui/commit/e24b4d67fa5e7bf58d99f3916eba80d869bf2ff5))


### Documentation

* updated readme regarding IAM usage ([559bde1](https://github.com/wenisch-tech/s3webui/commit/559bde15dc7c459cb63293b6e29ec05e6bb31c97))



Docker image: ghcr.io/wenisch-tech/s3webui:1.3.0


## v1.2.3 - 2026-09-15

### [1.2.3](https://github.com/wenisch-tech/s3webui/compare/v1.2.2...v1.2.3) (2026-09-15)


### Bug Fixes

* secretkey is displayed once after creation ([0ca7a72](https://github.com/wenisch-tech/s3webui/commit/0ca7a72084cc85358af276ac544098b1498241fd))



Docker image: ghcr.io/wenisch-tech/s3webui:1.2.3


## v1.2.2 - 2026-09-15

### [1.2.2](https://github.com/wenisch-tech/s3webui/compare/v1.2.1...v1.2.2) (2026-09-15)


### Bug Fixes

* updated deps ([96e7b47](https://github.com/wenisch-tech/s3webui/commit/96e7b47602365ad8203387d25242f0ffe09ee3e2))



Docker image: ghcr.io/wenisch-tech/s3webui:1.2.2


## v1.2.1 - 2026-09-11

### [1.2.1](https://github.com/wenisch-tech/s3webui/compare/v1.2.0...v1.2.1) (2026-09-11)


### Bug Fixes

* IAM section was invisible by default and unusable on Ceph ([f671554](https://github.com/wenisch-tech/s3webui/commit/f6715542f71a1f0a953da8b15be5e5b4fd4f1a2e))


### Documentation

* document IAM management, bucket policy and CORS editing ([b0f403a](https://github.com/wenisch-tech/s3webui/commit/b0f403a33d054e7d5e71e621cc7a2b600fa89b55))



Docker image: ghcr.io/wenisch-tech/s3webui:1.2.1


## v1.2.0 - 2026-09-10

## [1.2.0](https://github.com/wenisch-tech/s3webui/compare/v1.1.0...v1.2.0) (2026-09-10)


### Features

* optional IAM management for users, groups, policies and keys ([0b912af](https://github.com/wenisch-tech/s3webui/commit/0b912af047c2edd35bfdf31508de7e29c52131cf))


### Bug Fixes

* bucket card context menu showed a page loader that never cleared ([e2e8e08](https://github.com/wenisch-tech/s3webui/commit/e2e8e087eb9d4e015533c69e8e318896f885c8a8))



Docker image: ghcr.io/wenisch-tech/s3webui:1.2.0


## v1.1.0 - 2026-09-10

## [1.1.0](https://github.com/wenisch-tech/s3webui/compare/v1.0.6...v1.1.0) (2026-09-10)


### Features

* view and edit bucket policy and CORS config from the UI ([994c70f](https://github.com/wenisch-tech/s3webui/commit/994c70f6b25b784831114d5246707b53f841f4ff))



Docker image: ghcr.io/wenisch-tech/s3webui:1.1.0


## v1.0.6 - 2026-09-09

### [1.0.6](https://github.com/wenisch-tech/s3webui/compare/v1.0.5...v1.0.6) (2026-09-09)


### Bug Fixes

* Kubernetes health probes falsely marking the pod unhealthy ([475922a](https://github.com/wenisch-tech/s3webui/commit/475922a224ad242c7bbbfcdd65c893f94e53764a))



Docker image: ghcr.io/wenisch-tech/s3webui:1.0.6


## v1.0.5 - 2026-09-09

### [1.0.5](https://github.com/wenisch-tech/s3webui/compare/v1.0.4...v1.0.5) (2026-09-09)


### Bug Fixes

* skip-TLS flag missing from the own-credentials sign-in dialog ([172b6c2](https://github.com/wenisch-tech/s3webui/commit/172b6c2ae70c4fc8e8cc255becbf9ac980ae4822))



Docker image: ghcr.io/wenisch-tech/s3webui:1.0.5


## v1.0.3 - 2026-09-08

### [1.0.3](https://github.com/wenisch-tech/s3webui/compare/v1.0.2...v1.0.3) (2026-09-08)


### Bug Fixes

* restore OIDC login and stop it failing silently ([f8be383](https://github.com/wenisch-tech/s3webui/commit/f8be38338d3f2b26a6303b72d4ff6ea4bac2170b))



Docker image: ghcr.io/wenisch-tech/s3webui:1.0.3


## v1.0.2 - 2026-09-08

### [1.0.2](https://github.com/wenisch-tech/s3webui/compare/v1.0.1...v1.0.2) (2026-09-08)


### Build Systems

* pin Tomcat to 11.0.25, fixing CVEs Boot 4.1.1's BOM hasn't caught up to ([a8c8587](https://github.com/wenisch-tech/s3webui/commit/a8c8587b93ce91867931d3c9abbc69d59e7dad2a))



Docker image: ghcr.io/wenisch-tech/s3webui:1.0.2


## v1.0.1 - 2026-09-08

### [1.0.1](https://github.com/wenisch-tech/s3webui/compare/v1.0.0...v1.0.1) (2026-09-08)


### Build Systems

* upgrade to Spring Boot 4.1.1 / Spring Framework 7.0.8 ([52bdac1](https://github.com/wenisch-tech/s3webui/commit/52bdac121be9ef44c4900386fc5f7f623855df8c))



Docker image: ghcr.io/wenisch-tech/s3webui:1.0.1


## v0.6.26 - 2026-09-01

### [0.6.26](https://github.com/wenisch-tech/s3webui/compare/v0.6.25...v0.6.26) (2026-09-01)



Docker image: ghcr.io/wenisch-tech/s3webui:0.6.26


## v0.6.25 - 2026-08-24

### [0.6.25](https://github.com/wenisch-tech/s3webui/compare/v0.6.24...v0.6.25) (2026-08-24)


### Bug Fixes

* update dependencies ([596f11e](https://github.com/wenisch-tech/s3webui/commit/596f11eb91c943d27b28c939076cf9601df9fd7f))


### Documentation

* update renovate.json ([ef94a06](https://github.com/wenisch-tech/s3webui/commit/ef94a064887ebf20f90b4cf35bf4c313c6bcf89c))



Docker image: ghcr.io/wenisch-tech/s3webui:0.6.25


## v0.6.24 - 2026-08-24

### [0.6.24](https://github.com/wenisch-tech/s3webui/compare/v0.6.23...v0.6.24) (2026-08-24)


### Bug Fixes

* **deps:** update aws-java-sdk-v2 monorepo to v2.54.2 ([a99657f](https://github.com/wenisch-tech/s3webui/commit/a99657f3d52d534336cc28de0be625a6208b6cb2))



Docker image: ghcr.io/wenisch-tech/s3webui:0.6.24


## v0.6.22 - 2026-07-05

### [0.6.22](https://github.com/wenisch-tech/s3webui/compare/v0.6.21...v0.6.22) (2026-07-05)



Docker image: ghcr.io/wenisch-tech/s3webui:0.6.22


## v0.6.20 - 2026-07-02

### [0.6.20](https://github.com/wenisch-tech/s3webui/compare/v0.6.19...v0.6.20) (2026-07-02)


### Bug Fixes

* **deps:** update spring boot ([8a09727](https://github.com/wenisch-tech/s3webui/commit/8a097270e6408ffc2341e9cf21fe939cd524eb37))



Docker image: ghcr.io/wenisch-tech/s3webui:0.6.20


## v0.6.19 - 2026-06-22

### [0.6.19](https://github.com/wenisch-tech/s3webui/compare/v0.6.18...v0.6.19) (2026-06-22)



Docker image: ghcr.io/wenisch-tech/s3webui:0.6.19


## v0.6.14 - 2026-06-21

### [0.6.14](https://github.com/wenisch-tech/s3webui/compare/v0.6.13...v0.6.14) (2026-06-21)



Docker image: ghcr.io/wenisch-tech/s3webui:0.6.14


## v0.6.11 - 2026-06-21

### [0.6.11](https://github.com/wenisch-tech/s3webui/compare/v0.6.10...v0.6.11) (2026-06-21)



Docker image: ghcr.io/wenisch-tech/s3webui:0.6.11


## v0.6.7 - 2026-06-21

### [0.6.7](https://github.com/wenisch-tech/s3webui/compare/v0.6.6...v0.6.7) (2026-06-21)


### Bug Fixes

* **deps:** update dependency org.springframework.boot:spring-boot-starter-parent to v3.5.15 ([72bac30](https://github.com/wenisch-tech/s3webui/commit/72bac3083fc797ce8adac8645c357888c725cc74))



Docker image: ghcr.io/wenisch-tech/s3webui:0.6.7


## v0.6.5 - 2026-06-19

### [0.6.5](https://github.com/wenisch-tech/s3webui/compare/v0.6.4...v0.6.5) (2026-06-19)


### Bug Fixes

* **deps:** update aws-java-sdk-v2 monorepo to v2.46.14 ([4f66d4e](https://github.com/wenisch-tech/s3webui/commit/4f66d4e39aa22cc811d3102de4fc1186061d867a))



Docker image: ghcr.io/wenisch-tech/s3webui:0.6.5


## v0.6.3 - 2026-05-31

### [0.6.3](https://github.com/wenisch-tech/s3webui/compare/v0.6.2...v0.6.3) (2026-05-31)


### Bug Fixes

* **deps:** update dependency org.springframework.boot:spring-boot-starter-parent to v3.5.14 ([91dc375](https://github.com/wenisch-tech/s3webui/commit/91dc3752ea3e787d52434316195746e79598d427))



Docker image: ghcr.io/wenisch-tech/s3webui:0.6.3


## v0.6.1 - 2026-05-31

### [0.6.1](https://github.com/wenisch-tech/s3webui/compare/v0.6.0...v0.6.1) (2026-05-31)



Docker image: ghcr.io/wenisch-tech/s3webui:0.6.1


## v0.6.0 - 2026-04-17

## [0.6.0](https://github.com/wenisch-tech/s3webui/compare/v0.5.3...v0.6.0) (2026-04-17)


### Features

* added support for multiple OIDC provider and added Readme for chart ([d9dcd9b](https://github.com/wenisch-tech/s3webui/commit/d9dcd9bb133ead84f84b7ffb91df67d88e75cc22))
* added support for multiple OIDC providers ([987f467](https://github.com/wenisch-tech/s3webui/commit/987f4673e93905bd3c377c49bab5bd4cb3ea579b))



Docker image: ghcr.io/wenisch-tech/s3webui:0.6.0


## v0.5.3 - 2026-04-17

### [0.5.3](https://github.com/wenisch-tech/s3webui/compare/v0.5.2...v0.5.3) (2026-04-17)



Docker image: ghcr.io/wenisch-tech/s3webui:0.5.3


## v0.5.2 - 2026-04-17

### [0.5.2](https://github.com/wenisch-tech/s3webui/compare/v0.5.1...v0.5.2) (2026-04-17)


### Documentation

* Updated footer and license information ([b12ace3](https://github.com/wenisch-tech/s3webui/commit/b12ace3b32d043b0bdaf71cf0a03bbdd491ee0d1))



Docker image: ghcr.io/wenisch-tech/s3webui:0.5.2


## v0.5.1 - 2026-04-14

### [0.5.1](https://github.com/wenisch-tech/s3webui/compare/v0.5.0...v0.5.1) (2026-04-14)


### Bug Fixes

* Update tomcat version to 10.1.53 ([fba423d](https://github.com/wenisch-tech/s3webui/commit/fba423df894a5f9c578a85f6c483798e017cf0f5))



Docker image: ghcr.io/wenisch-tech/s3webui:0.5.1


## v0.5.0 - 2026-04-13

## [0.5.0](https://github.com/wenisch-tech/s3webui/compare/v0.4.0...v0.5.0) (2026-04-13)


### Features

* added pie-chart display for buckets based on size ([83721db](https://github.com/wenisch-tech/s3webui/commit/83721db74c70aea0c93a670fb1a7e99a3125a10f))
* Buckets in Object storage can be displayed as list ([41d3f4e](https://github.com/wenisch-tech/s3webui/commit/41d3f4e0b8a262c679b6c4cccc695400fbab0d63))
* S3 URL is displayed as starter for Breadcrumb rather then just Buckets ([410cc23](https://github.com/wenisch-tech/s3webui/commit/410cc23e1cccfedce861f0dd3d1dff43f20103ea))
* size of buckets and total size are displayed on the landing ([3199735](https://github.com/wenisch-tech/s3webui/commit/319973520f497e16a0ebd6a26a1430808c671574))


### Bug Fixes

* Delete bucket button is now working as intended from context menu ([d5148b8](https://github.com/wenisch-tech/s3webui/commit/d5148b8fe5bb201e9a8fbcd0f21e296b32ca7926))
* fixed a problem that lead to card not being properly clickable ([a5d8a59](https://github.com/wenisch-tech/s3webui/commit/a5d8a59d80f1c3d0ea02366de4ae03607123ff86))



Docker image: ghcr.io/wenisch-tech/s3webui:0.5.0


## v0.4.0 - 2026-04-13

## [0.4.0](https://github.com/wenisch-tech/s3webui/compare/v0.3.1...v0.4.0) (2026-04-13)


### Features

* Improved error handling and showing dedicated message for duplicated buckets ([672eb00](https://github.com/wenisch-tech/s3webui/commit/672eb00b29c79c6973926f4b0fa5b0b9d0f8211b))


### Bug Fixes

* minor updates to light colorscheme and setting light as default ([fc4b46f](https://github.com/wenisch-tech/s3webui/commit/fc4b46fc613a1222c13607539bff1c2332dc0812))



Docker image: ghcr.io/wenisch-tech/s3webui:0.4.0


## v0.3.1 - 2026-04-11

### [0.3.1](https://github.com/wenisch-tech/s3webui/compare/v0.3.0...v0.3.1) (2026-04-11)


### Bug Fixes

* update image references in chart ([812939b](https://github.com/wenisch-tech/s3webui/commit/812939b207f0f83116c08ddac8de3bcec0d4b2b4))



Docker image: ghcr.io/wenisch-tech/s3webui:0.3.1


## v0.3.0 - 2026-03-31

## [0.3.0](https://github.com/wenisch-tech/s3webui/compare/v0.2.12...v0.3.0) (2026-03-31)


### Features

* added additional dialog that is shown when no s3 env variables have been set ([c8a23fb](https://github.com/wenisch-tech/s3webui/commit/c8a23fb8e733e0af290dec9c14d80fb522095563))


### Bug Fixes

* rebased ui changes ([edf179d](https://github.com/wenisch-tech/s3webui/commit/edf179dfcde20d508b9588fe602b1c40adf13120))



Docker image: ghcr.io/wenisch-tech/s3webui:0.3.0


## v0.2.12 - 2026-03-29

### [0.2.12](https://github.com/wenisch-tech/s3webui/compare/v0.2.11...v0.2.12) (2026-03-29)


### Bug Fixes

* updated default repository used in chart ([2f423c0](https://github.com/wenisch-tech/s3webui/commit/2f423c0362dc0fd01722f7961eb7fb39c6e11052))



Docker image: ghcr.io/wenisch-tech/s3webui:0.2.12


## v0.2.11 - 2026-03-28

### [0.2.11](https://github.com/wenisch-tech/s3webui/compare/v0.2.10...v0.2.11) (2026-03-28)


### Documentation

* updated licensing information and added logo ([0cdd902](https://github.com/wenisch-tech/s3webui/commit/0cdd90286a8e447617111b7cd302111f3b400e3e))



Docker image: ghcr.io/wenisch-tech/s3webui:0.2.11


## v0.2.10 - 2026-03-27

### [0.2.10](https://github.com/wenisch-tech/s3webui/compare/v0.2.9...v0.2.10) (2026-03-27)


### Documentation

* clean Readme.md after refactoring ([92f594e](https://github.com/wenisch-tech/s3webui/commit/92f594ec6a9151126646b54a1bb3e5f0ddf7934a))



Docker image: ghcr.io/wenisch-tech/s3webui:0.2.10


## v0.2.9 - 2026-03-27

### [0.2.9](https://github.com/wenisch-tech/s3webui/compare/v0.2.8...v0.2.9) (2026-03-27)


### Documentation

* update README with screenshots, badges, and CVE scanning note ([f334143](https://github.com/wenisch-tech/s3webui/commit/f334143f954643d2fee8edc3cf6974d7472160fb))



Docker image: ghcr.io/wenisch-tech/s3webui:0.2.9


## v0.2.8 - 2026-03-27

### [0.2.8](https://github.com/wenisch-tech/s3webui/compare/v0.2.7...v0.2.8) (2026-03-27)


### Bug Fixes

* updated security config to ignore csrf again for api endpoint ([3f49067](https://github.com/wenisch-tech/s3webui/commit/3f49067682f8657a4089c242846dc42c9d7d1040))



Docker image: ghcr.io/wenisch-tech/s3webui:0.2.8


## v0.2.7 - 2026-03-27

### [0.2.7](https://github.com/wenisch-tech/s3webui/compare/v0.2.6...v0.2.7) (2026-03-27)


### Bug Fixes

* removed incompatible API Call for cleintbuilder trustmanager ([a4a6cbb](https://github.com/wenisch-tech/s3webui/commit/a4a6cbbe9156595a2fb63767900355be890da858))



Docker image: ghcr.io/wenisch-tech/s3webui:0.2.7


## v0.2.5 - 2026-03-27

### [0.2.5](https://github.com/wenisch-tech/s3webui/compare/v0.2.4...v0.2.5) (2026-03-27)


### Bug Fixes

* when s3 insecure is activated ssl context also switched to accept all certs ([6f25ec9](https://github.com/wenisch-tech/s3webui/commit/6f25ec9487790dbcb16072000e02423aaa1a021d))



Docker image: ghcr.io/wenisch-tech/s3webui:0.2.5


## v0.2.4 - 2026-03-27

### [0.2.4](https://github.com/wenisch-tech/s3webui/compare/v0.2.3...v0.2.4) (2026-03-27)


### Bug Fixes

* use default driver for building docker ([06ff065](https://github.com/wenisch-tech/s3webui/commit/06ff0652703f05a4cf0b23367e5efc857ac49e8d))



Docker image: ghcr.io/wenisch-tech/s3webui:0.2.4


## v0.1.2 - 2026-03-27

### [0.1.2](https://github.com/wenisch-tech/s3webui/compare/v0.1.1...v0.1.2) (2026-03-27)


### Bug Fixes

* fixed release step by unifying secret usage ([c1854bd](https://github.com/wenisch-tech/s3webui/commit/c1854bd3b478d64f5cb9459d539cba8c279a1376))



Docker image: ghcr.io/wenisch-tech/s3webui:0.1.2


## v0.1.0 - 2026-03-27

## [0.1.0](https://github.com/wenisch-tech/s3webui/compare/v0.0.3...v0.1.0) (2026-03-27)


### Features

* Added support for skip TLS ([45ee66f](https://github.com/wenisch-tech/s3webui/commit/45ee66f76ff5c3fe81f101f9d1fbed66337c6008))



Docker image: ghcr.io/wenisch-tech/s3webui:0.1.0


## v0.0.3 - 2026-03-27

### [0.0.3](https://github.com/wenisch-tech/s3webui/compare/v0.0.2...v0.0.3) (2026-03-27)


### Bug Fixes

* updated spring security and manually fixed some functions handling bytestreams ([fcca281](https://github.com/wenisch-tech/s3webui/commit/fcca281da1e72591bbe83622ecc2c8ec4dd4897e))



Docker image: ghcr.io/wenisch-tech/s3webui:0.0.3


## v0.0.2 - 2026-03-27

### [0.0.2](https://github.com/wenisch-tech/s3webui/compare/v0.0.1...v0.0.2) (2026-03-27)



Docker image: ghcr.io/wenisch-tech/s3webui:0.0.2

