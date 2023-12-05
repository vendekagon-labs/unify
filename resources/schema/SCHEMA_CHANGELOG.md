# 1.0.0

TODO (coming in wrapping of 1.0 work)

## Added

Idents

```Clojure
{:db/ident  :clinical.observation.ir.recist/irCR}
{:db/ident  :clinical.observation.ir.recist/irPR}
{:db/ident  :clinical.observation.ir.recist/irSD}
{:db/ident  :clinical.observation.ir.recist/irNN}
{:db/ident  :clinical.observation.ir.recist/irPD}
{:db/ident  :clinical.observation.ir.recist/irNE}
{:db/ident  :clinical.observation.ir.recist/irND}
{:db/ident  :clinical.observation.event.reason/lost-to-follow-up}
{:db/ident  :clinical.observation.event.reason/censored-unknown}
{:db/ident  :clinical.observation.event.reason/dead}
{:db/ident  :clinical.observation.event.reason/progressed}
{:db/ident  :clinical.observation.event.reason/switched-therapy}
{:db/ident  :clinical.observation.event.reason/study-period-ended}
{:db/ident  :clinical.observation.event.reason/new-lesion} 
{:db/ident  :subject.cause.of.death/related-to-cancer}
{:db/ident  :subject.cause.of.death/unrelated-to-cancer}
{:db/ident  :timepoint.type/eot}
{:db/ident  :variant.impact/high}
{:db/ident  :variant.impact/moderate}
{:db/ident  :variant.impact/low}
{:db/ident  :variant.impact/modifier}
```

Attributes

```Clojure
:clinical-observation/metastasis-gdc-anatomic-sites
:subject/cause-of-death
:subject/menopause
:clinical-observation/ir-recist
:clinical-observation/bor
:clinical-observation/dfi
:clinical-observation/event-reason
:clinical-observation/ttf
:therapy/dlt-evaluable
:therapy/safety-evaluable
:measurement/pg-mL
:dataset/clinical-observation-sets
:clinical-observation-set/name
:clinical-observation-set/description
:clinical-observation-set/clinical-observations
:clinical-observation/dcb
```

## Modified

Attributes

- `:variant/impact` has been modified from string to ref (enum)

## Removed

```Clojure
:dataset/clinical-observations
:clinical-observation/responder
```

# 0.3.0

## Added

Idents
```Clojure

:sample.specimen/cell-line
:sample.specimen/xenograft
:clinical.observation.disease.stage/zero
:clinical.observation.disease.stage/zeroA
:clinical.observation.disease.stage/zeroIS
:clinical.observation.disease.stage/iA
:clinical.observation.disease.stage/iA1
:clinical.observation.disease.stage/iA2
:clinical.observation.disease.stage/iB
:clinical.observation.disease.stage/iB1
:clinical.observation.disease.stage/iB2
:clinical.observation.disease.stage/iC
:clinical.observation.disease.stage/iiA
:clinical.observation.disease.stage/iiA1
:clinical.observation.disease.stage/iiA2
:clinical.observation.disease.stage/iiB
:clinical.observation.disease.stage/iiC
:clinical.observation.disease.stage/iiiA
:clinical.observation.disease.stage/iiiB
:clinical.observation.disease.stage/iiiC
:clinical.observation.disease.stage/iiiC1
:clinical.observation.disease.stage/iiiC2
:clinical.observation.disease.stage/iiiD
:clinical.observation.disease.stage/ivA
:clinical.observation.disease.stage/ivB
:clinical.observation.disease.stage/ivC
:variant.type/indel
:technology/DNA-seq
:technology/impact-targeted-panel
:technology/foundation-targeted-panel
:therapy.previous/naive
:therapy.previous/chemotherapy
:therapy.previous/pd1-pdl1
:therapy.previous/ctla4
:therapy.previous/targeted-therapy
:therapy.previous/car-t
:therapy.previous/treated
:timepoint.type/diagnosis
:study.day.reference.event/enrollment
:study.day.reference.event/diagnosis
:study.day.reference.event/surgery
```

Attributes

```Clojure
:subject/freetext-disease
:subject/metastatic-disease
:clinical-observation/freetext-adverse-event
:clinical-observation/absolute-monocyte-count
:clinical-observation/absolute-leukocyte-count
:therapy/previous
:drug-regimen/freetext-drug
:measurement-set/terra-workflow
:measurement/absolute-cn
:measurement/total-reads
:measurement/tmb-total
:measurement/tmb-snv
:measurement/tmb-indel
:measurement/percent-of-total-cells
:sample/freetext-anatomic-site
:dataset/study-days
:clinical-observation/study-day
:study-day/id
:study-day/day
:study-day/reference-event
:sample/study-day
:so-sequence-feature/name
:so-sequence-feature/id
:measurement/tcr-count
```

## Removed

Idents

```Clojure
:timepoint.type/pre-treatment	
:timepoint.type/post-treatment
:timepoint.type/pre-surgery
:timepoint.type/post-surgery
:variant.consequence/dominant-negative
:variant.consequence/gain-of-function
:variant.consequence/lethal
:variant.consequence/loss-of-heterozygosity
:variant.consequence/loss-of-function
:variant.consequence/null
:who.grade/i
:who.grade/ii
:who.grade/iii
:who.grade/iv
```

Attributes

```Clojure
:clinical-observation/date
:measurement-set/analysis-user
:measurement-set/analysis-date
:measurement-set/analysis-confluence
:measurement/peak-read-count
:measurement/percentage
:sample/date
```

## Modified

Idents

- The `stage` namespace has been renamed to `clinical.observation.disease.stage`
- The `hgnc.locus.group` namespace has been renamed to `gene.hgnc.locus.group`
- The `treatment.setting` namespace has been renamed to `treatment.regimen.setting`
- The `sex` namespace has been renamed to `subject.sex`
- The `race` namespace has been renamed to `subject.race`
- The `ethnicity` namespace has been renamed to `subject.ethnicity`
- The `recist` namespace has been renamed to `clinical.observation.recist`
- The `smoker` namespace has been renamed to `subject.smoker`
- The `ae.grade` namespace has been renamed to `clinical.observation.ae.grade`
- The `assembly` namespace has been renamed to `genomic.coordinate.assembly`
- The `technology` namespace has been renamed to `assay.technology`


Attributes

```Clojure
:clinical-observation/age -> :subject/age
:measurement-set/analysis-github -> :measurement-set/analysis-repository
:measurement-set/analysis-fc-namespace -> :measurement-set/terra-namespace
:measurement-set/analysis-fc-workspace -> :measurement-set/terra-workspace
:measurement-set/analysis-fc-subm-id  -> :measurement-set/terra-submission-id
:measurement-set/analysis-cellengine -> :measurement-set/cellengine-experiment-revision-id
:variant/consequence -> :variant/so-consequences
```