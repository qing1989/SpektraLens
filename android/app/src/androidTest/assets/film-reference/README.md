# Independent film rendering references

Input `chart.rgb8`: project-generated 96 x 64 RGB8 test chart, CC0-1.0.
The other RGB8 files are deterministic reference renders using SpektraLab
267ee24380091e5bee87658d00f6c33d77321194 native Metal on Apple M4, with safe
math and precise floating-point functions. Their measured profile sources
are Andrea Volpato's spektrafilm and Hanze Qiu's SpektraLab, retained in the
SpektraLab submodule. Reference renders are provided under CC BY-SA 4.0;
see UPSTREAMS.md and the submodule's license/SPEKTRAFILM_LICENSE.txt.

All 20 supported stocks are included, using each film's target paper and
direct scan for reversal film. Five additional cases cover exposure up/down,
gamma, halation and explicit auto exposure. Grain and stochastic paper glare
are disabled to make individual pixels comparable. This does not validate
random grain distributions or the behavior of a particular physical GPU.

The manifest records parameters and hashes. Test code is GPL-3.0-or-later.
Regenerate with scripts/generate_film_reference.py --library /path/to/the/
upstream/libspektrafilm_engine.dylib --resources /path/to/upstream/engine/resources.
This generator rejects a Vulkan library and requires the upstream safe-math
probe to pass. See docs/胶片显影对照.md for this capture's loader adaptation.
