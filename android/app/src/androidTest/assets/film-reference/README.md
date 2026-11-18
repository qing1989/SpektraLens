# Independent film rendering references

Input `chart.rgb8`: project-generated 96 x 64 RGB8 test chart, CC0-1.0.
The other RGB8 files are deterministic reference renders using SpektraLab
267ee24380091e5bee87658d00f6c33d77321194 native Metal on Apple M4, with safe
math and precise floating-point functions. Their measured profile sources
are Andrea Volpato's spektrafilm and Hanze Qiu's SpektraLab, retained in the
SpektraLab submodule. Reference renders are provided under CC BY-SA 4.0;
see UPSTREAMS.md and the submodule's license/SPEKTRAFILM_LICENSE.txt.

All 25 supported stocks are included, using each film's target paper and
direct scan for reversal film. Five additional cases cover exposure up/down,
gamma, halation and explicit auto exposure. Grain and stochastic paper glare
are disabled to make individual pixels comparable. This does not validate
random grain distributions or the behavior of a particular physical GPU.

The four black-and-white references use SpektraLab's product profiles and
Multigrade IV RC with the upstream grade-2 pack (C/M/Y = 0/0/68), one grain
layer, and EDR disabled. The original product NOTICE and profile metadata
are retained in the submodule and application assets. All 25 pre-existing
color/reference-case hashes were unchanged when adding these four captures.
Android tests additionally check default effects, equal-size live/full
rendering, and PNG16 export; their device execution is tracked separately
in docs/验证记录.md.

CineStill 800T is an alias of the separately versioned Vision3 500T data.
Its base reference keeps halation disabled, so it uses the same measured
image bytes as Vision3 500T; CineStill-specific back-reflection and
`halation_headroom_ev` behavior are covered by the upstream engine tests.

The manifest records parameters and hashes. Test code is GPL-3.0-or-later.
Regenerate with scripts/generate_film_reference.py --library /path/to/the/
upstream/libspektrafilm_engine.dylib --resources /path/to/upstream/engine/resources.
This generator rejects a Vulkan library and requires the upstream safe-math
probe to pass. See docs/胶片显影对照.md for this capture's loader adaptation.
