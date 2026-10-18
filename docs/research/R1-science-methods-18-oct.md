# Science and maths methods review (research R1, 18 Oct 2026)

Written by Codex (deep research, read only) for Aditya Singh. Checked by Claude: all calculated numbers recomputed and matched; spot-checked sources opened and matched: Zhou et al. 2019 (AMT), EPA-454/R-99-005 Table 6-7a (sigma_A E 3.8-7.5 deg, F < 3.8 deg), Yee et al. 2014 (572 km -> 44 km, rate within 20%), Newman et al. 2025 (5.82 m vs 11.09 m; 90 CPUs, 250 GB). Could not open: Hutchinson, Liu and Chen 2019 IEEE TCST full text (its numbers are not used in plans). Code findings (calibration uses its own kernel, pair NOT_NEEDED rule, diagonal whitening) were confirmed in the code. File links below point to the author's Mac.

**FuelPrint’s best scientific upgrade is to model the measurement process and its uncertainty consistently, then improve dispersion and inference.** Adding MCMC to the present observation model would produce more elaborate answers without fixing its main weaknesses.

For the department round, I recommend preserving the scenario physics while fixing response consistency, covariance, and calibration. For the finale, add turbulence-aware dispersion, Bayesian evidence over the existing explanations, and an information-based planner as separately evaluated versions.

I read the requested contract sections and science code. This was read-only: **files changed: none; repository tests were not run**, because they generate build artifacts. Numerical checks identified below as **calculated** are analytic calculations, not published experimental results. Engineering hours are estimates; FuelPrint runtime remains **UNVERIFIED until benchmarked**.

Linked evidence below was opened and the relevant methods/results inspected, except items explicitly marked **UNVERIFIED**. Reading a paper does not mean reproducing its experiment.

---

**The code inspection changes the priorities.**

| Finding | Evidence and consequence |
|---|---|
| Calibration does not exercise the actual simulated world or instrument chain. | [CalibrationRunner.java:151](/Users/aditya/dev/fuelprint-innotech/server/src/main/java/fuelprint/science/rank/CalibrationRunner.java:151) generates concentrations with the same steady kernel used for inference, adds noise/background, and averages. It does not propagate puff transport, line delay, chamber mixing, or sensor response. |
| The inverse response differs from the simulated instrument. | [ResponseModel.java:19](/Users/aditya/dev/fuelprint-innotech/server/src/main/java/fuelprint/science/rank/ResponseModel.java:19) uses one 60-second T90 and one line delay for both gases, without chamber mixing. Stationary tracks return the steady concentration immediately. That shortcut is valid under its steady-state assumption; it cannot represent changing concentrations at a stationary inlet. |
| Correlated error is supported by the numerical machinery but not used in fitting. | [FitEngine.java:154](/Users/aditya/dev/fuelprint-innotech/server/src/main/java/fuelprint/science/rank/FitEngine.java:154) requests diagonal whitening. [Whitening.java](/Users/aditya/dev/fuelprint-innotech/server/src/main/java/fuelprint/science/rank/Whitening.java) already accepts a full covariance matrix. |
| The final survivor list differs from the thresholded compatibility set. | [CompatRanker.java:122](/Users/aditya/dev/fuelprint-innotech/server/src/main/java/fuelprint/science/rank/CompatRanker.java:122) rejects a pair as `NOT_NEEDED` whenever either constituent single fits. A true pair can therefore pass the cutoff and subsequently disappear from the displayed survivor list. |
| The planner predicts a settled stationary measurement. | [IntervalScorer.java:80](/Users/aditya/dev/fuelprint-innotech/server/src/main/java/fuelprint/science/plan/IntervalScorer.java:80) constructs a fixed target track. Its score does not propagate the incoming instrument state along the proposed route. |
| Current scenario tests do not establish end-to-end attribution. | [ScenarioRunsTest.java](/Users/aditya/dev/fuelprint-innotech/server/src/test/java/fuelprint/science/scenario/ScenarioRunsTest.java) explicitly tests world, sensors, detection and capture, not analysis outcomes. Some ranker tests instead construct observations from the ranker’s own kernel. |
| The learned ranker is specified, not implemented in the inspected locations. | `fuelprint/ai/` contains `package-info.java`; `tools/train/` and `resources/models/` were absent. |

Also, SC1’s **3 m/s** wind and the calibration domain’s **2–5 m/s** are distinct from the very-low-wind regime where the field literature finds strong meandering.

---

**1. Source term estimation: retain the useful compatibility calculation, then add uncertainty integration.**

FuelPrint currently performs constrained fitting and set membership: fit nonnegative rates for each known explanation, select its best nuisance member, and compare its discrepancy with a threshold. This is a reasonable interpretable baseline. Its weakness is that the best nuisance member represents whichever weather setting helps most, without accounting for how much uncertainty supports that setting. Extra sources can improve fit, while four fixed locations may not identify their individual contributions. Neither fit nor survival is a posterior probability.

The strongest sources are:

- **Hutchinson, Michael; Oh, Hyondong; Chen, Wen-Hua. 2017. “A review of source term estimation methods for atmospheric dispersion events using static or mobile sensors.” *Information Fusion* 36, 130–148.** The review organizes optimization, Bayesian inference, dispersion uncertainty and mobile sensing. It emphasizes the loss of performance between ideal simulations and experimental data. It supplies no new experimental accuracy result. [Opened full paper](https://udrc.eng.ed.ac.uk/sites/udrc.eng.ed.ac.uk/files/attachments/freestyle-page/20170118/WP2_Runxiao_ESV2015.PDF).

- **Keats, Andrew; Yee, Eugene; Lien, Fue-Sang. 2007. “Bayesian inference for source determination with applications to a complex urban environment.” *Atmospheric Environment* 41(3), 465–479.** Uses adjoint source–receptor fields and Metropolis–Hastings sampling, with observational and model uncertainty. Validation includes a MUST water-channel experiment and real **Joint Urban 2003** measurements. The urban example uses **nine sampler sites**: true release **2.0 g/s**, MCMC estimate **1.985 g/s**, posterior SD **0.049 g/s**. These are one case’s results, not general accuracy. Assigned concentration uncertainties range from **2% to 70%**, representing discrepancy assumptions rather than demonstrated sensor precision. [Opened author full text, especially pp. 476–477](https://www.researchgate.net/publication/222208558_Bayesian_inference_for_source_determination_with_applications_to_a_complex_urban_environment).

- **Yee, Eugene. 2010. “Validation of a Bayesian inferential framework for multiple source reconstruction using FFT-07 data.” Proceedings of the 13th Harmonisation Conference, H13-214, 916–920.** Uses reversible-jump MCMC to infer source count, locations, strengths and operating times. On a real **100-detector FUSION Field Trial 2007** example, the modal source count is the correct **four**, with posterior mass approximately **0.6**. The paper describes **50,000 posterior samples** in the illustrated calculation. Its embedded table was not reliably extractable, so precise location-error numbers are **UNVERIFIED and omitted**. This does not establish four-sensor performance. [Opened full paper](https://www.harmo.org/Conferences/Proceedings/_Paris/publishedSections/H13-214-abst.pdf).

- **Hirst, Bill; Jonathan, Philip; González del Cueto, Fernando; Randell, David; Kosut, Oliver. 2013. “Locating and quantifying gas emission sources using remotely obtained concentration data.” *Atmospheric Environment* 74, 141–158.** Combines plume mixtures, a spatial background model, sparse initialization and RJMCMC, including wind-direction bias. Real airborne methane applications cover landfills and a flare. A **synthetic** ten-source case recovers **nine of ten** sources. The real flare application infers a wind correction interval of **−18.12° to −17.2°**; this is an inferred parameter, not a measured attribution error. Real applications also produce downwind “ghost” sources, illustrating transport-model failure. Independently known field emission-rate accuracy was not established. [Opened manuscript](https://arxiv.org/pdf/1211.1409).

- **Hutchinson, Michael; Liu, Cunjia; Chen, Wen-Hua. 2019. “Source term estimation of a hazardous airborne release using an unmanned aerial vehicle.” *Journal of Field Robotics* 36(4), 797–817.** The opened abstract establishes outdoor automated UAV experiments using sequential Monte Carlo and alternative analytical dispersion models. **Full methods and numerical accuracy: UNVERIFIED.** I would not quote localization numbers from it. [Opened publisher abstract](https://onlinelibrary.wiley.com/doi/10.1002/rob.21844).

For FuelPrint, **enumerating the existing explanations is preferable to RJMCMC**. Their locations and maximum source count are already tightly constrained.

A useful model is

\[
y=F_M(q,\eta)+b+\delta+\epsilon,\qquad q\ge0,
\]

where \(M\) is an explanation, \(\eta\) contains wind/dispersion nuisance parameters, \(b\) is background, \(\delta\) is model discrepancy, and \(\epsilon\) is instrument error.

Bayesian model comparison requires integration:

\[
Z_M=p(y\mid M)
=\sum_{j=1}^{27}w_j
 \int_{q\ge0}p(y\mid q,\eta_j,M)p(q\mid M)\,dq,
\]

\[
p(M\mid y)=
\frac{p(M)Z_M}{\sum_Lp(L)Z_L}.
\]

This is different from taking the best member’s likelihood. Use **proper, finite rate priors**; an improper flat prior cannot supply comparable model evidences. Equal nuisance weights are an explicit modeling assumption, not a measured wind distribution.

For singles and pairs, integration is low dimensional. It can factor by gas only when the error model supports conditional independence; a shared cross-gas background generally prevents that simplification.

For sequential inference, importance weights update as

\[
\widetilde w_i^{\,t}
=w_i^{t-1}p(y_t\mid\theta_i),\qquad
w_i^t=\frac{\widetilde w_i^{\,t}}{\sum_j\widetilde w_j^{\,t}}.
\]

Use only genuinely new observations, or explicitly account for overlapping windows. Reusing the same observations as fresh evidence creates overconfidence. Effective sample size,

\[
N_{\rm eff}=1/\sum_i(w_i^t)^2,
\]

provides a useful degeneracy diagnostic.

**“None fits” still needs an absolute check.** A closed Bayesian list distributes all its mass among known models even when every model is wrong. Preserve the mismatch gate and UNKNOWN row. Background-only, an unknown source, faulty wind, and inadequate dispersion are different explanations; posterior normalization cannot distinguish them automatically.

**Implementation oracles:** identical models and equal priors give equal weights; duplicating a nuisance point while splitting its weight leaves evidence unchanged; scalar Gaussian regression constrained to \(q\ge0\) agrees with a truncated-normal solution; deliberately incompatible observations fail every absolute-fit check despite normalized model weights summing to one.

**Effort:** approximately **24–40 hours** for a shadow evidence calculation, proper priors and diagnostics after correcting the observation operator. Cached low-dimensional calculations are plausible on the laptop; latency is **UNVERIFIED**. Arbitrary-location RJMCMC and urban CFD inversion should not enter the department build.

---

**2. Dispersion: improve turbulence and transport before adding more fitted parameters.**

The present reflected Gaussian kernel is mathematically coherent for its assumptions. Its constant diffusivities make spread proportional to \(\sqrt{t}\) immediately, omit stability and wind profiles, and approximate all observations using one steady wind. The puff world already preserves transport history, which is a good foundation. Another important detail is that source heights are named **`effective_height_m`**: adding plume rise directly to those values would double count rise unless physical stack heights are separately supplied.

Five useful primary/official sources are:

- **Scire, Joseph S.; Strimaitis, David G.; Yamartino, Robert J. 2000. *A User’s Guide for the CALPUFF Dispersion Model, Version 5*. Earth Tech.** Relevant methods are §§2.1–2.4. It prioritizes measured turbulence, then similarity-derived turbulence, then stability-class curves; it also documents nonsteady puff transport and Briggs rise. Its puff-versus-slug demonstration is a numerical comparison, not field accuracy. [Opened guide](https://www.calpuff.org/calpuff/download/CALPUFF_UsersGuide.pdf).

- **U.S. EPA. 2026. *AERMOD Model Formulation*. EPA-454/B-26-003, July 2026.** Stable dispersion uses Gaussian distributions with vertically varying meteorology, effective layer averages, plume rise, reflection and lateral meander. It is much more than a replacement sigma formula. The live document is **2026**, although older pages link to the same mutable filename. [Opened formulation](https://gaftp.epa.gov/Air/aqmg/SCRAM/models/preferred/aermod/aermod_mfd.pdf), [current official version page](https://www.epa.gov/scram/air-quality-dispersion-modeling-preferred-and-recommended-models).

- **U.S. EPA. 2003. *AERMOD: Latest Features and Evaluation Results*. EPA-454/R-03-003.** Table 2 reports the following modeled/observed **Robust Highest Concentration** ratios for the historical model: Prairie Grass **0.89**, Kincaid SF₆ **0.77**, Indianapolis **1.11**, Tracy **1.04**, each for the table’s one-hour comparison. These are upper-concentration-distribution statistics, not source-localization errors or current-version results. Tracy provides especially relevant stable-dominated elevated-release evidence. [Opened evaluation, printed pp. 18–19](https://gaftp.epa.gov/Air/aqmg/SCRAM/models/preferred/aermod/aermod_mep.pdf).

- **Irwin, John S. 1979. *Scheme for Estimating Dispersion Parameters as a Function of Release Height*. EPA-600/4-79-062.** Separates atmospheric turbulence, buoyancy and directional shear; includes implementation code. Its characteristic vertical time changes with effective height. Its elevated-release comparisons do not establish universal stable near-field accuracy. [Opened report and FORTRAN](https://nepis.epa.gov/Exe/ZyPURL.cgi?Dockey=20015NQK.TXT).

- **U.S. EPA. 1993. *Selection Criteria for Mathematical Models Used in Exposure Assessments: Atmospheric Dispersion Models*. EPA/600/8-91/038.** Part II describes P–G and Briggs parameterizations, their assumptions and coefficients. These historical empirical curves are useful comparison models, not proof that one land-use class represents Kundli. [Opened report, Part II §2 and Table 9](https://nepis.epa.gov/Exe/ZyPURL.cgi?Dockey=30001LIV.TXT).

A practical progression is:

**First, add alternative spread models for evaluation.** For travel time \(t=x/u\), CALPUFF documents

\[
\sigma_{yt}=\sigma_vt f_y(t),\qquad
\sigma_{zt}=\sigma_wt f_z(t),
\]

with its Draxler option

\[
f_y=\left[1+0.9\sqrt{t/1000}\right]^{-1},
\]

\[
f_z=\left[1+0.945(t/100)^{0.806}\right]^{-1}
\quad\text{for stable conditions}.
\]

Time is in seconds. These are the documented implementation’s forms, not universal elevated-stack laws. For small directional fluctuations,

\[
\sigma_v\simeq u\,\sigma_\theta,
\]

with \(\sigma_\theta\) in **radians**; that approximation breaks down for broad directional distributions.

For an inexpensive empirical comparison, the EPA rural Briggs forms include

\[
\begin{array}{c|cc}
&\sigma_y&\sigma_z\\ \hline
E&0.06x(1+0.0001x)^{-1/2}&0.03x(1+0.0003x)^{-1}\\
F&0.04x(1+0.0001x)^{-1/2}&0.016x(1+0.0003x)^{-1}
\end{array}
\]

where distances are metres. CALPUFF’s urban E/F comparison is

\[
\sigma_y=0.11x(1+0.0004x)^{-1/2},\qquad
\sigma_z=0.08x(1+0.0015x)^{-1/2}.
\]

These alternatives can predict very different ground concentrations. Do not select one merely because it preserves SC1.

A physically useful intermediate model follows Taylor dispersion:

\[
\sigma_y^2(t)=\sigma_{y0}^2+
2\sigma_v^2T_L
\left[t-T_L(1-e^{-t/T_L})\right].
\]

It gives ballistic initial growth, \(\sigma_y^2-\sigma_{y0}^2\sim\sigma_v^2t^2\), and long-time diffusion with \(K_y=\sigma_v^2T_L\). The field-based time-scale evidence and its limitations appear in topic 3.

**Second, introduce plume rise only with the required inputs.** AERMOD defines

\[
F_m=\frac{T_a}{T_s}w_s^2r_s^2,\qquad
F_b=gw_sr_s^2\frac{T_s-T_a}{T_s},
\]

requiring stack radius, exit speed and temperature, ambient temperature, and stratification.

CALPUFF’s Briggs implementation gives

\[
\Delta h_n(x)=
\left[
\frac{3F_mx}{\beta_j^2u_s^2}
+\frac{3F_bx^2}{2\beta_1^2u_s^3}
\right]^{1/3},
\]

\[
\Delta h_{sf}=
\left[
\frac{3F_m}{\beta_j^2u_s\sqrt S}
+\frac{6F_b}{\beta_2^2u_sS}
\right]^{1/3},
\]

where \(\beta_1\approx0.6\), \(\beta_2\approx0.36\),

\[
\beta_j=1/3+u_s/w_s,\qquad
S=(g/T_a)\,\partial\theta/\partial z>0.
\]

Its stable calm buoyant branch is

\[
\Delta h_{sf,\mathrm{calm}}=4F_b^{1/4}S^{-3/8}.
\]

The finite-wind expression must not be extrapolated through \(u_s=0\). A full implementation also needs the documented transition and momentum/downwash branches.

Irwin adds buoyancy spread as

\[
\sigma'_y=\sigma'_z=\Delta h/3.5,
\]

combined with atmospheric spread in variance. His vertical characteristic time is

\[
T_0=
\begin{cases}
50\ \mathrm{s},&H_e\le50\ \mathrm m,\\
(3H_e-50)/2\ \mathrm{s},&50<H_e<150\ \mathrm m,\\
200\ \mathrm{s},&H_e\ge150\ \mathrm m.
\end{cases}
\]

**Third, use history-aware inverse predictions for turning winds.** Predict unit-source responses through the public wind history and instrument chain, cache them, and fit emission amplitudes against those responses. Existing puff advection can support this. It will matter more for the finale’s changing winds than a steady-plume sigma replacement.

AERMOD’s meander construction,

\[
C=(1-f_r)C_{\rm coherent}+f_rC_{\rm random},
\]

is an averaged concentration model. It should not be mistaken for a second-by-second simulated wind trajectory.

**Implementation oracles:**

- Ground reflection gives \(\partial C/\partial z=0\) at \(z=0\).
- The reflected steady plume conserves cross-sectional flux:
  \[
  u\int_{-\infty}^{\infty}\int_0^\infty C\,dz\,dy=Q.
  \]
- For the Draxler option at \(t=100\) s, \(\sigma_v=0.2\) m/s and \(\sigma_w=0.1\) m/s, calculated spreads are **15.568988 m** and **5.141388 m**.
- For the stable-rise expression with \(F_b=10\), \(F_m=0\), \(u_s=3\), \(S=10^{-4}\), in the corresponding SI units, calculated rise is **115.560212 m**.
- Compare a steady puff train with the matching continuous-source solution under timestep refinement. Account for longitudinal diffusion rather than demanding identity with an approximation that neglects it.

For real validation, EPA publishes Prairie Grass, Kincaid and Indianapolis evaluation datasets. I opened the index and README; **archive contents and FuelPrint reproduction are UNVERIFIED**. Prairie Grass is principally a near-ground-release check; Kincaid and Indianapolis are more relevant to elevated buoyant stacks. [Opened dataset README](https://gaftp.epa.gov/AIR/aqmg/SCRAM/models/preferred/aermod/eval_databases/evalreadme.txt).

**Effort:** spread alternatives **8–16 hours**; plume rise with separate inputs **12–24 hours**; history-aware inverse responses **16–32 hours**. Closed-form spread/rise is cheap. Puff inversion needs caching and benchmarking.

**Original-source access gaps:** Draxler’s 1976 “Determination of atmospheric diffusion parameters,” *Atmospheric Environment* 10, 99–105, DOI `10.1016/0004-6981(76)90226-2`: **UNVERIFIED full methods**. Briggs’s 1975 “Plume rise predictions,” in *Lectures on Air Pollution and Environmental Impact Analyses*, pp. 59–111: **UNVERIFIED original text**. The equations above were checked through the opened official/model implementation documents.

---

**3. Wind variability: 15-minute means cannot determine the current per-second drift.**

The calibration generator assumes stationary direction SD **0.5°** and one-second correlation **0.995**. That is a declared stochastic wind model, not turbulence recovered from CPCB data. Fifteen-minute means do not identify within-period variance, its spectrum, or its correlation time. Moreover, a station’s temporal correlation is Eulerian; plume dispersion requires Lagrangian parcel statistics. Equating them is another assumption.

The most useful sources are:

- **U.S. EPA. 2000. *Meteorological Monitoring Guidance for Regulatory Modeling Applications*. EPA-454/R-99-005.** Recommends wind sampling on **1–5-second** scales. Its hourly stability statistic uses the RMS of four **within-15-minute** direction SDs. At 10 m and reference roughness 0.15 m, initial classification cutpoints include E: **3.8–7.5°**, F: **below 3.8°**; wind-speed adjustments also apply. These are classification thresholds, not typical Murthal observations. [Opened guidance, §§6.1–6.4](https://www.epa.gov/sites/default/files/2020-10/documents/mmgrma_0.pdf).

- **Anfossi, Domenico; Oettl, Dietmar; Degrazia, Gervasio; Goulart, Alessandro. 2005. “An Analysis of Sonic Anemometer Observations in Low Wind Speed Conditions.” *Boundary-Layer Meteorology* 114, 179–203.** Analyzes one year at Graz and approximately one month at Tisby. Below roughly **1–2 m/s**, horizontal correlations show meandering and negative lobes. Tisby fits yield a characteristic meander period around **2,000 seconds** in the specified fitted regime. That period is neither a universal decorrelation time nor a Lagrangian time. The paper explicitly questions simple Eulerian-to-Lagrangian conversions at low wind. [Opened author full text](https://www.researchgate.net/publication/226120532_An_Analysis_Of_Sonic_Anemometer_Observations_In_Low_Wind_Speed_Conditions).

- **Irwin 1979**, cited in topic 2, explicitly connects dispersion to sampling duration and release height. **AERMOD 2026**, also cited there, distinguishes turbulence treatment, profiles and meander. Neither supports deriving a unique second-scale direction SD from station means.

- **Hanna, Steven R. 1983. “Lateral Turbulence Intensity and Plume Meandering During Stable Conditions.” *Journal of Climate and Applied Meteorology* 22, 1424–1430.** Citation/abstract located, but **full methods and numerical findings UNVERIFIED** because direct access failed. DOI `10.1175/1520-0450(1983)022<1424:LTIAPM>2.0.CO;2`. I have not used its numerical claims.

The defensible implementation separates **resolved mean wind** from **assumed unresolved variability**.

Use vector wind components for aggregation and interpolation. A mean of 359° and 1° must lie near north. Preserve calm or missing direction as undefined where appropriate.

For the current scalar small-angle model,

\[
d_{k+1}=\rho d_k+
\sigma_\infty\sqrt{1-\rho^2}\,\epsilon_k,
\qquad
\rho=e^{-\Delta t/T_E},
\quad\epsilon_k\sim N(0,1).
\]

Calculated for \(\rho=0.995\), \(\Delta t=1\) s:

\[
T_E=199.499582\ \mathrm{s},
\]

\[
\sigma_{\rm innovation}=0.099874922\,\sigma_\infty.
\]

Thus the present 0.5° stationary SD uses innovation SD **0.049937461°**, not 0.5°.

There is also an averaging distinction. For \(N\) samples,

\[
\operatorname{Var}(\bar d)=
\frac{\sigma_\infty^2}{N^2}
\left[N+2\sum_{k=1}^{N-1}(N-k)\rho^k\right].
\]

For \(N=900,\rho=0.995\), the calculated block-mean variance is **0.346141174 times stationary variance**. Expected within-block demeaned variance, with divisor \(N\), is therefore **0.653858826 times stationary variance**.

Consequently, setting stationary SD equal to an EPA within-period SD threshold does not reproduce that statistic. Under this assumed AR model, convert a target within-period variance using the corresponding factor.

For sensitivity testing, I would use several within-period spreads and correlation times rather than select one “correct” value. For example, **1°, 3°, 5°** and **30, 200, 600 seconds** are proposed engineering stress cases, **not measured Murthal parameters**.

For very low winds, use stochastic \(u,v\) components rather than an unbounded Gaussian direction. Anfossi’s fitted correlation form is

\[
R_E(\tau)=e^{-p\tau}\cos(q\tau),\qquad
\int_0^\infty R_E(\tau)\,d\tau=\frac{p}{p^2+q^2}.
\]

An ordinary positive-\(\rho\) AR(1) cannot reproduce its negative lobes. A cheap proposed implementation is a two-state damped rotation with Gaussian innovations; its first component has that covariance. Parameters still require high-frequency measurements or explicit assumptions.

For dispersion, the relevant relationship is

\[
\sigma_y^2(t)=
2\sigma_v^2\int_0^t(t-\tau)R_L(\tau)\,d\tau.
\]

This uses **\(R_L\)**, not automatically the station’s **\(R_E\)**.

**Implementation oracles:** stationary variance, lag correlations, exact block-mean variance, timestep-invariant correlation time, circular means, and negative correlation for the oscillatory model. Evaluate many independent realizations.

**Effort:** assumptions and sensitivity audit **4–8 hours**; vector stochastic wind **8–16 hours**; high-frequency data QA/fitting **8–24 hours after obtaining data**. All are laptop-cheap.

**UNVERIFIED:** Murthal station measurement height, obstructions, direction-averaging convention, raw-data availability, and a site-specific typical stable-night \(\sigma_\theta\). No defensible unique conversion from its 15-minute means was established.

---

**4. Where to measure next: retain the interval planner as a conservative baseline.**

The current planner asks whether two explanations’ prospective prediction boxes can be separated, per procedure minute. This is understandable and avoids invented probabilities. Its outer boxes can be pessimistic: overlapping boxes may contain very different predictive distributions. Conversely, its one-standard-uncertainty allowance is not a hard error bound under Gaussian noise. The interpretation must specify the combined pairwise error budget. The larger immediate issue is predicting the measurement after the actual route, lag, purge and settling procedure.

The strongest sources are:

- **Vergassola, Massimo; Villermaux, Emmanuel; Shraiman, Boris I. 2007. “‘Infotaxis’ as a strategy for searching without gradients.” *Nature* 445, 406–409.** Maintains a Bayesian source-location map and chooses actions by expected entropy reduction. Tests use a computational encounter model and measured mixing-flow data. This is not an industrial autonomous UAV field validation. Its encounter likelihood should not be transplanted directly to slowly filtered SO₂ concentrations. [Opened author full text](https://www.researchgate.net/publication/6551674_%27Infotaxis%27_as_a_strategy_for_searching_without_gradients).

- **Hutchinson, Michael; Liu, Cunjia; Chen, Wen-Hua. 2019. “Information-Based Search for an Atmospheric Release Using a Mobile Robot: Algorithm and Experiments.” *IEEE Transactions on Control Systems Technology* 27(6), 2388–2402.** Uses particle inference and prospective information calculations, with an intermittent-signal likelihood. It uses **10,000 estimation particles** and **100 prospective-measurement samples**. Real indoor robot experiments comprise nine incense trials; location was typically within **10 cm**. Absolute emission rate was unknown and the detector was uncalibrated for that quantity. Slow sensor recovery caused bias. [Opened full paper, §VI](https://scispace.com/pdf/information-based-search-for-an-atmospheric-release-using-a-18c53ssvfc.pdf).

- **Moraud, Eduardo Martin; Martinez, Dominique. 2010. “Effectiveness and Robustness of Robot Infotaxis for Searching in Dilute Conditions.” *Frontiers in Neurorobotics* 4, article 1.** A real robot succeeded in **20 of 21** searches within **150 steps** in a **5 × 4 m** arena. Crucially, it detected a heat source with a fast temperature sensor, not SO₂. This supports physical information-driven navigation, with limited transfer to minute-response gas instruments. [Opened full paper](https://pmc.ncbi.nlm.nih.gov/articles/PMC2856589/).

- **van Hove, Alouette; Aalstad, Kristoffer; Pirk, Norbert. 2026. “Actively inferring methane sources with drones.” *Environmental Data Science* 5, e2.** Uses a particle filter, observation-error model and information-based/deep-Q planning. Real borehole measurements calibrate the observation model, but the planning comparison uses **25,000 simulated flights**. With 108 steps, reported RL medians include location CRPS **10 m**, rate CRPS **0.6 kg/h**, and entropy reduction **8.9 nats**. CRPS is not mean absolute localization error. Real adaptive-flight validation remains future work. [Opened full paper](https://www.cambridge.org/core/journals/environmental-data-science/article/actively-inferring-methane-sources-with-drones/B9636E970B3888E7503464A41633719E), [opened associated code](https://github.com/AlouetteUiO/active).

A laptop-cheap upgrade should optimize information about **which explanation fits**, rather than spending actions solely to refine nuisance wind parameters.

For feasible action \(a\), calculate the response-filtered predictive distribution

\[
p(z\mid M,D,a)=
\int p(z\mid\theta,M,a)p(\theta\mid M,D)\,d\theta.
\]

Then

\[
U(a)=
\sum_Mp_M
\int p(z\mid M,D,a)
\log
\frac{p(z\mid M,D,a)}
{\sum_Lp_Lp(z\mid L,D,a)}\,dz,
\]

and select

\[
a^*=\arg\max_{a\ {\rm feasible}}
\frac{U(a)}{\text{complete procedure time}}.
\]

This is expected information in **nats**, not a probability of catching a source.

Use the existing feasible actions, route restrictions, battery checks and tie rules. Begin with a fixed weighted predictive mixture and **64–128 prospective outcomes per action**, an engineering starting point requiring convergence checks. Cache predictions and use log-sum-exp for mixture likelihoods.

Initially run this in shadow mode against interval separation. Where every registered model fails, the system should investigate background, coverage or model discrepancy rather than force a known-source attribution.

**Implementation oracles:**

- Identical predictive distributions give \(U=0\).
- Two equally weighted, perfectly distinguishable explanations give \(U=\log2\).
- Permuting source labels leaves utility unchanged.
- Increased independent noise cannot increase exact information.
- The linear-Gaussian parameter case agrees with
  \[
  I(\theta;z)=\tfrac12\log\det
  (I+\Sigma_\theta H^\top R^{-1}H).
  \]
- Correlated readings provide less information than the same number of independent readings.

**Effort:** shadow EIG evaluator **12–20 hours** after the observation/posterior machinery exists; sequential inference another **16–24 hours**. Discrete-action evaluation is plausible on the laptop; runtime is **UNVERIFIED**. Deep RL and long-horizon planning are unnecessary for this exhibition.

---

**5. The cutoff: its rank is conformal-style, but the guarantee is narrower than “95% correct.”**

The stored threshold is **7.095634863439867**, from **60 eligible episodes**, using rank **58**. Recomputing that number proves reproducibility, not coverage on the puff world, different winds, adaptively selected measurements or real observations. Diagonal residual scaling and the shared calibration/inference kernel further limit its scope.

The relevant sources are:

- **Angelopoulos, Anastasios N.; Bates, Stephen. 2021, revised 2022. “A Gentle Introduction to Conformal Prediction and Distribution-Free Uncertainty Quantification.” arXiv:2107.07511.** Gives split-conformal construction, finite-sample marginal coverage, calibration-size effects and distribution-shift limitations. It does not make posterior correctness claims for individual predictions. [Opened revision, §§1 and 3](https://arxiv.org/pdf/2107.07511).

- **Shafer, Glenn; Vovk, Vladimir. 2008. “A Tutorial on Conformal Prediction.” *Journal of Machine Learning Research* 9, 371–421.** Explains exchangeability and valid prediction sets. Exchangeability means the joint distribution is invariant to permutation; correlated observations within an episode need not invalidate episode-level calibration, but successive windows are not automatically exchangeable independent calibration episodes. [Opened full paper](https://jmlr.org/papers/volume9/shafer08a/shafer08a.pdf).

- **Ganesan, A. L.; Rigby, M.; Zammit-Mangion, A.; Manning, A. J.; Prinn, R. G.; Fraser, P. J.; Harth, C. M.; Kim, K.-R.; Krummel, P. B.; Li, S.; Mühle, J.; O’Doherty, S. J.; Park, S.; Salameh, P. K.; Steele, L. P.; Weiss, R. F. 2014. “Characterization of uncertainties in atmospheric trace gas inversions using hierarchical Bayesian methods.” *Atmospheric Chemistry and Physics* 14, 3855–3864.** Infers emissions together with discrepancy scales and autocorrelation. Real AGAGE SF₆ applications infer error correlation times of **6 hours to 1 day**—regional model-error times, not local wind turbulence. Equation 6 uses exponentially decaying covariance. [Opened paper](https://acp.copernicus.org/articles/14/3855/2014/acp-14-3855-2014.pdf).

- **Yee, Eugene; Hoffman, Ian; Ungar, Kurt. 2014. “Bayesian Inference for Source Reconstruction: A Real-World Application.” *International Scholarly Research Notices*, article 507634.** Uses eight xenon measurements from three operational stations and MCMC. Initial discrepancy assumptions produce approximately **572 km** location error and uncertainty intervals excluding truth. Adding inferred multiplicative model-error factors improves this example to approximately **44 km**, with rate error within **20%** and intervals containing truth. This continental-scale example demonstrates why discrepancy treatment matters; it is not a local-stack accuracy benchmark. [Opened full paper, Tables 1–2](https://pdfs.semanticscholar.org/f1f7/48a2b6255bf266512318fe3b64da0a5c7960.pdf).

For fixed score \(s(x,M)\), split conformal uses

\[
k=\lceil(m+1)(1-\alpha)\rceil,\qquad
\widehat q=s_{(k)},
\]

and

\[
\mathcal C(x)=\{M:s(x,M)\le\widehat q\}.
\]

For \(m=60,\alpha=0.05\), **\(k=58\)**. Under exchangeability and the required fixed-pipeline conditions,

\[
P\{M_{\rm true}\in\mathcal C(X_{\rm new})\}\ge0.95.
\]

With continuous tie-free scores, the corresponding rank probability is **58/61 = 0.950819672**, calculated.

This is a **marginal inclusion guarantee over the specified episode distribution**. It does not mean that a surviving factory is 95% likely responsible, that a singleton is 95% correct conditional on being a singleton, or that real-world unknown sources are detected at that rate.

The code’s `min(m,k)` differs from the general construction when \(k>m\): the appropriate threshold is then infinite, or the implementation must refuse the insufficient calibration size. This exception does not affect the current \(m=60\).

FuelPrint has four additional qualifications:

1. Calibration is conditional on its eligibility rule and successful fitting.
2. Its calibration measurement locations are sampled differently from an adaptive planner’s selections.
3. Puff/sensor-generated deployment observations differ from its calibration generator.
4. Pair removal after thresholding means the final displayed survivor set need not retain the true support even when the raw compatibility set does.

Do not claim conformal coverage for the final candidates without evaluating that complete construction.

The covariance upgrade should use the actual linear operations:

\[
\Sigma_{\rm windows}=BKB^\top+\Sigma_{\rm model},
\]

where \(K\) describes raw instrument/background covariance and \(B\) performs filtering, averaging and background subtraction.

For example,

\[
\operatorname{Cov}(X_i-B,X_j-B)
=
\operatorname{Cov}(X_i,X_j)
-\operatorname{Cov}(X_i,B)
-\operatorname{Cov}(X_j,B)
+\operatorname{Var}(B).
\]

Even independent signal windows become correlated when they subtract the same background estimate. Shared ambient background can also correlate sensors and gases.

A practical first implementation can estimate model-discrepancy covariance from **separate development simulations**, shrink it toward a stable positive-definite structure, and freeze it before final calibration. Do not freely inflate discrepancy until every observation fits.

The familiar statistic

\[
Q=r^\top\Sigma^{-1}r
\]

has a chi-square interpretation for specified Gaussian errors under the appropriate fixed-model conditions. Ordinary linear least squares with known covariance has a residual degrees-of-freedom adjustment. **NNLS boundaries, nuisance-member selection and estimated error scales make a naive \(\chi^2_{n-p}\) threshold inappropriate here.** Episode-level empirical calibration is the more defensible immediate approach.

**Implementation oracles:** rank 58 for 60 scores; correct small-sample handling; whitening agrees with direct \(r^\top\Sigma^{-1}r\); reused background produces the expected off-diagonal covariance; repeated independent calibration/test experiments attain the expected rank-based coverage distribution. Assess raw-set coverage and final-list coverage separately.

**Effort:** covariance **4–8 hours**; full-chain calibration and independent coverage evaluation **8–16 hours**, depending on simulation cost. A 60-by-60 covariance has only **3,600 entries**; matrix storage is trivial. Simulation and fitting dominate runtime.

---

**6. Learned ranking and neural inference: simulation success must remain labeled simulation success.**

The contract’s ranker restriction is scientifically useful: it can reorder survivors but cannot rescue rejected explanations, change outcomes or remove UNKNOWN. The main risks are learning source-frequency shortcuts, simulator artifacts, or repeated-window leakage. Held-out seeds alone do not test transfer to different dispersion, turbulence or instrument assumptions.

Five useful sources are:

- **Newman, Thomas; Nemeth, Christopher; Jones, Matthew; Jonathan, Philip. 2025. “Deep Learning Surrogates for Real-Time Gas Emission Inversion.” arXiv:2506.14597, preprint.** A neural **forward surrogate** is embedded in sequential importance resampling; this is not amortized posterior estimation. On real Chilbolton methane releases, final-particle mean distance is **5.82 m**, versus **11.09 m** for its Gaussian baseline. Total reported computations are **83.3 versus 173.6 minutes**; surrogate preparation uses **90 CPUs and 250 GB RAM**. These results do not establish an 8 GB laptop workflow. [Opened paper, Tables 1–2](https://arxiv.org/html/2506.14597v1), [authors’ repository](https://github.com/NewmanTHP/Deep-Learning-Surrogates-for-Real-Time-Gas-Emission-Inversion).

- **Edwards, C.; Smith, R. C. 2025. “Rapid Parameter Inference with Uncertainty Quantification for a Radiological Plume Source Identification Problem.” arXiv:2502.17492.** Uses **400,000 simulated examples** and 18 radiation detectors. Held-out simulation results include regression-network mean location error **10.44 m** and mass MAE **0.2515 g**. A 10,000-prediction uncertainty calculation takes **10.9 seconds**, versus **1,674 seconds** for the illustrated DRAM calculation. It demonstrates synthetic radiological inference, not field SO₂ performance. [Opened preprint](https://arxiv.org/html/2502.17492v1).

- **Kim, Suhyeon; Jang, Siho; Kim, Eung Soo. 2024. “Source Term Estimation based on Invertible Neural Network.” Transactions of the Korean Nuclear Society Spring Meeting, Jeju, 9–10 May.** A conditional invertible network generates source-parameter posterior samples. Training/testing uses **700,000/100,000 simulated examples**, with assumed observation error within **±5%**. The presented posterior comparison concerns one selected test case; aggregate coverage and real-field accuracy are not established. [Opened methods paper](https://www.kns.org/files/pre_paper/51/24S-221-%EA%B9%80%EC%88%98%ED%98%84.pdf).

- **Greenberg, David S.; Nonnenmacher, Marcel; Macke, Jakob H. 2019. “Automatic Posterior Transformation for Likelihood-Free Inference.” *Proceedings of ICML*, PMLR 97, 2404–2414.** Provides sequential neural posterior estimation with proposal correction. Benchmarks include synthetic dynamical/statistical simulators, not pollution field trials. It establishes a method, not atmospheric accuracy. [Opened paper](https://proceedings.mlr.press/v97/greenberg19a/greenberg19a.pdf).

- **Burges, Christopher J. C. 2010. “From RankNet to LambdaRank to LambdaMART: An Overview.” Microsoft Research Technical Report MSR-TR-2010-82.** Describes pairwise ranking and boosted-tree ranking. It explicitly presents no experimental results. It is older than the requested neural-inference interval but directly relevant to implementing FuelPrint’s restricted ranking task. [Opened report](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/MSR-TR-2010-82.pdf).

**Research finding:** I did not establish a field-calibrated amortized posterior for few-sensor industrial SO₂ attribution, or a real pollution-source learning-to-rank benchmark. This is a search limitation, not proof that none exists.

For FuelPrint, start with a small pairwise model:

\[
s_\phi(e,x)=f_\phi(\text{public features}),
\]

\[
\mathcal L(\phi)=
\sum_{\text{nights}}\sum_{e_i\succ e_j}
\log\left(1+e^{-[s_\phi(e_i,x)-s_\phi(e_j,x)]}\right).
\]

Useful proposed features are fit residuals and their structure, nuisance sensitivity, geometry, QC/missingness, fitted rates and source count. Test historical counts with an ablation; they can encode a shortcut.

Group all observations from a night together. Separate training, model selection, calibration and final testing. In addition to seed/source splits, hold out meteorological blocks and test shifted spread models, lag, noise, faults and unknown-source episodes.

If every known survivor is wrong on an unknown-source night, do not invent a preferred known-source label. Report ranking metrics only on clearly defined eligible cases, alongside the physics engine’s true-support survival rate. Otherwise the ranker can appear accurate simply because difficult nights were excluded.

A later neural posterior would train something like

\[
\mathcal L_{\rm NPE}=
-\sum_j\log q_\phi(\theta_j\mid x_j),
\]

with proposal correction when simulations are not drawn from the prior. Its coverage must be checked against exact inference on small cases and independent field data before making physical probability claims.

**Implementation oracles:** identical features tie; candidate permutations preserve ranking; rejected candidates never reappear; outcomes and UNKNOWN remain unchanged; Java scores match exported-model scores. For neural posteriors, compare against exact small-problem quadrature and test empirical coverage.

**Effort:** ranker and evaluation **16–28 hours if the data/artifact pipeline exists**. Since that pipeline was absent in the inspected locations, allow approximately **32–56 hours** overall. Inference should be inexpensive, but remains **UNVERIFIED**. A credible NPE project requires **40–80+ hours**, excluding better simulation and field-data preparation.

---

**7. Measurement reality: 60-second SO₂ response is plausible; the noise interpretation needs care.**

The simulated instrument has a volume-based line, an 8-second chamber T90, 60-second SO₂ T90, 30-second CO₂ T90 and correlated electrical noise. These are useful teaching components. The immediate problem is their inconsistent use in inference, planning and calibration. Real SO₂ UAV studies also demonstrate that response, recovery, background measurement and installed-platform interference matter; a rapid concentration update is not necessarily an independent measurement.

Six useful sources are:

- **Zhou, Fan; Pan, Shuang; Chen, Wen; Ni, Xiaolong; An, Bo. 2019. “Monitoring of compliance with fuel sulfur content regulations through unmanned aerial vehicle (UAV) measurements of ship emissions.” *Atmospheric Measurement Techniques* 12, 6113–6124.** Reports electrochemical SO₂ specifications of **T90 <1 s**, resolution **0.001 ppm**, accuracy **±0.25 ppm**. These are reported specifications, not measured noise SD. Protocol: **3-minute background**, then **5 minutes each** near two funnel distances. Across **23 comparisons**, fuel-sulfur deviations were generally below **0.03 percentage points by mass**. Attribution came from sampling an identified ship’s funnel; the result is fuel-composition accuracy, not six-source localization accuracy. [Opened methods/results](https://amt.copernicus.org/articles/12/6113/2019/).

- **Zhou, Fan; Hou, Lijuan; Zhong, Rui; Chen, Wen; Ni, Xiaolong; Pan, Shuang; Zhao, Min; An, Bo. 2020. “Monitoring the compliance of sailing ships with fuel sulfur content regulations using unmanned aerial vehicle (UAV) measurements of ship emissions in open water.” *Atmospheric Measurement Techniques* 13, 4899–4909.** The second-generation instrument reports SO₂ **T90 ≤30 s**, accuracy **±0.3 ppm**. Sampling generally follows the funnel for approximately **5 minutes**. More than 40 sailing-ship attempts yielded **27 usable datasets**. Separate validation on **11 berthed ships** generally achieved fuel-sulfur deviations ≤0.03 percentage points, with two exceptions. [Opened paper](https://amt.copernicus.org/articles/13/4899/2020/).

- **Rüdiger, Julian; Tirpitz, Jan-Lukas; de Moor, J. Maarten; Bobrowski, Nicole; Gutmann, Andreas; Liuzzo, Marco; Ibarra, Melissa; Hoffmann, Thorsten. 2018. “Implementation of electrochemical, optical and denuder-based sensors and sampling techniques on UAV for volcanic gas measurements: examples from Masaya, Turrialba and Stromboli volcanoes.” *Atmospheric Measurement Techniques* 11, 2441–2462.** Real campaigns use sensors with CO₂ T90 approximately **2 s** and SO₂ **<20 s**. The faster series is convolved with an exponential response before comparing gas ratios. This directly supports matching response functions rather than comparing instantaneous readings. [Opened paper, §3.2](https://amt.copernicus.org/articles/11/2441/2018/).

- **Roberts, T. J.; Lurton, T.; Giudice, G.; Liuzzo, M.; Aiuppa, A.; Coltelli, M.; Vignelles, D.; Salerno, G.; Couté, B.; Chartier, M.; Baron, R.; Saffell, J. R.; Scaillet, B. 2017. “Validation of a novel Multi-Gas sensor for volcanic HCl alongside H₂S and SO₂ at Mt. Etna.” *Bulletin of Volcanology* 79, 36.** Laboratory and field measurements support explicit first-order response modeling; calibrated SO₂-AE T90 is approximately **12 s**. The reported SO₂-A4 noise is **15 ppbv at ±2 SD**. More complex response inversion can amplify noise. [Opened updated article](https://link.springer.com/article/10.1007/s00445-017-1114-z). **Separate erratum contents: UNVERIFIED.**

- **Alphasense. *SO₂-B4/SO₂-B4+ Sulfur Dioxide Sensor Technical Specifications*, version 1.1.** Specifies **T90 <60 s** for the stated step test and noise **5 ppb at ±2 SD**, using its low-noise electronics. This supports the plausibility of FuelPrint’s T90, but not its AR innovation variance or installed-UAV performance. [Opened official product page and linked datasheet](https://www.alphasense.com/products/view-by-target-gas/so2-b4).

- **Mori, Toshiya; Hashimoto, Takeshi; Terada, Akihiko; Yoshimoto, Mitsuhiro; Kazahaya, Ryunosuke; Shinohara, Hiroshi; Tanaka, Ryo. 2016. “Volcanic plume measurements using a UAV for the 2014 Mt. Ontake eruption.” *Earth, Planets and Space* 68, 49.** Real flights identify motor-related electrical interference in SO₂ measurements, reduced by shielding. A low-concentration ratio result had **30–50% error** near the noise level. This supports installed-system QC, not treating every received value as usable plume evidence. [Opened paper](https://link.springer.com/article/10.1186/s40623-016-0418-0).

The upgrade should predict exactly what the instrument reports:

\[
\mu_{g,w}=
\frac1{T_w}\int_w
\left[h_{\mathrm{sensor},g}*
h_{\mathrm{chamber}}*C_g\right](t-d_g)\,dt.
\]

At constant flow,

\[
d_g=60V_{\rm line}/Q_g.
\]

From FuelPrint’s line dimensions, calculated volume is **0.018849556 L**. Therefore:

| Gas | Flow | Calculated line delay |
|---|---:|---:|
| SO₂ | 0.5 L/min | 2.261946711 s |
| CO₂ | 0.3 L/min | 3.769911184 s |

Use the volume-based transport implementation when flow changes.

For chamber and sensor time constants

\[
\tau_c=8/\ln10,\qquad \tau_s=T90_g/\ln10,
\]

the continuous unit-step response after delay \(d\) is

\[
y(t)=
1-\frac{
\tau_s e^{-(t-d)/\tau_s}
-\tau_c e^{-(t-d)/\tau_c}}
{\tau_s-\tau_c},
\quad t\ge d,
\]

and zero before \(d\).

With current parameters, calculated response at **60 seconds after the inlet step** is:

- SO₂: **0.8741518063**
- CO₂: **0.9817878395**

These provide a strong convergence oracle. Sequential discrete updates need not equal the continuous cascade exactly at finite timestep.

Noise also needs an explicit statistical definition:

\[
\epsilon_t=\rho\epsilon_{t-1}+\eta_t,\qquad
\sigma_\infty^2=\frac{\sigma_\eta^2}{1-\rho^2}.
\]

For FuelPrint’s \(\rho=0.8\) and SO₂ innovation SD 5 ppb, calculated stationary SD is **8.333333 ppb**. The 30-s mean SD is **4.21316 ppb**; subtracting an independent equivalent background mean gives enhancement SD **5.95831 ppb**. Shared backgrounds require the covariance treatment in topic 5.

The manufacturer’s **5 ppb at ±2 SD** is therefore not the code’s **5 ppb innovation SD**. Keep the synthetic allocation labeled; do not silently replace one with the other.

The existing long settling period makes a 30-s collection window more plausible than a 30-s sample immediately after entering a plume. Still, the prediction must include route history and actual settling. Ship fuel-sulfur ratios also rely on combustion/composition assumptions that do not establish a unique signature for six arbitrary factories.

**Implementation oracles:** the cascade values above; a single first-order element reaches 0.9 at T90; zero flow moves no sampled volume; complete recovery preserves integrated pulse area; unequal sensor responses preserve a constant input gas ratio when complete background-subtracted pulse integrals are used.

**Effort:** matched gas-specific observation operators and analytic checks **6–12 hours**, overlapping the calibration work above. Filtering is cheap. Adsorption, cross-sensitivity and platform-dependent noise require measurements; arbitrary extra parameters would add complexity without evidence.

---

**The ranked build plan is as follows.**

All hours below are engineering estimates and overlap with the topic estimates. No proposed runtime has been measured in FuelPrint.

| Rank | Before the department round | Estimate | Why |
|---:|---|---:|---|
| 1 | Add complete SC1–SC3 regression paths from puff world through QC, ranking and planning. | 3–5 h | Existing component tests do not prove the required final analysis outcomes. |
| 2 | Match gas-specific line, chamber, sensor and window response across observations, inverse predictions and planner predictions. | 6–10 h | Fixes a directly observed internal inconsistency. |
| 3 | Construct covariance for averaging and reused/shared backgrounds. | 4–8 h | Prevents correlated observations being treated as independent evidence. |
| 4 | Rebuild calibration through the actual simulated measurement chain; evaluate independent nights and separately record raw-set/final-list coverage. | 6–10 h | Gives the cutoff an honest, testable synthetic scope. |
| 5 | Audit wind-spread/time-scale sensitivity, benchmark latency, and freeze a versioned build. | 3–5 h | Exposes assumptions without changing scenario physics at the last minute. |

This is approximately **24–36 hours with overlap**, a reasonable two-day agent-work target. Calibration throughput is the main runtime uncertainty. If work expands, prioritize the complete regression path, response consistency and honest calibration before adding alternative inference.

The release checks must preserve:

- **SC1:** the prescribed alarm and initial ambiguity, followed by the required useful drone measurement and provisional S02 result.
- **SC2:** rejected SO₂, valid CO₂, and stale raw values never becoming valid evidence.
- **SC3:** no fixed-box alarm and no forced single-source conclusion.

Keep scenario seeds out of training/calibration. If a new scientific model removes the required SC1 ground signal, retain it as a separately evaluated model rather than tuning its uncertainty until the scripted ending returns.

| Rank | Finale work for 30 October | Estimate | Why |
|---:|---|---:|---|
| 1 | History-aware inverse predictions plus explicit turbulence/spread alternatives. | 16–32 h | Directly addresses turning winds and forward/inverse mismatch. |
| 2 | A small learned ranker with grouped and shifted-condition evaluation. | 32–56 h with the currently missing pipeline | Delivers the contracted learning feature while retaining physics-based rejection. |
| 3 | Bayesian evidence over the existing known explanations, initially in shadow mode. | 24–40 h | Integrates nuisance uncertainty and penalizes unnecessary flexibility. |
| 4 | Shadow EIG planning using complete procedure predictions. | 12–20 h after inference exists | Tests whether distribution-aware planning improves on interval separation. |
| 5 | An external elevated-release benchmark and, if inputs exist, separate physical-stack plume rise. | 12–24+ h each | Supplies evidence beyond agreement with FuelPrint’s own simulator. |

Skip for this exhibition:

| Item | Why |
|---|---|
| Full urban CFD/CALMET/CALPUFF integration | Meteorological inputs and validation dominate the available time. |
| Arbitrary-location RJMCMC | Four fixed sensors do not justify the added identifiability and implementation burden. |
| Full neural posterior deployment | No verified field-calibrated few-sensor SO₂ result was established here. |
| Deep-RL flight planning | Cheaper discrete-action information planning provides a clearer comparison. |
| Reconstructing second-scale turbulence from 15-minute means | The missing variability is not identifiable from those means alone. |
| Adding Briggs rise to existing effective heights | It double counts rise without separate physical-stack inputs. |
| Physical source-probability percentages on screen | The present evidence supports synthetic compatibility and ranking, not those probabilities. |

**Contract changes needed before promoting replacement methods:** revised FP-COMPAT survival/source-count semantics, replacement FP-PLAN scoring, additional physical-stack/turbulence inputs where they affect public interfaces, and any probability presentation would need an owner-approved contract revision. No contract was changed.

**Remaining unverified work:** FuelPrint performance on external field datasets, laptop latency after these upgrades, actual Murthal turbulence, installed sensor behavior, inaccessible original-paper methods identified above, and end-to-end SC1–SC3 outcomes after any implementation.