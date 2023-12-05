rm(list = ls())
library(data.table)

# -- NOTE --
# TCGA portions of this script no longer run. They will need to be re-written
# if there are more substantive changes in the future. The script:
# ref_data_filter.R re-processes _processed_ files to match new/different ref
# data.

####### config paths for where processed data lives and how much to downsample
pd.dir <- '~/azure-datasets'
tcga.dir <- '~/azure-datasets/tcga-paad'
num.cytof.meas <- 1000
num.variants <- 1000
setwd(file.path(pd.dir, 'template', 'processed'))


####### Copy samples and measurements files from Rizvi and TCGA exactly as they are

# Rizvi
for (f in c('samples.txt',
            'subjects.txt',
            'therapies.txt',
            'clinical_observations.txt')){
    system(sprintf('cp %s %s',
                     file.path(pd.dir, 'rizvi2015', 'processed', f),
                     file.path(pd.dir, 'template', 'processed', paste0('rizvi-', f))))
}
for (f in c('genomic_coords.txt',
            'variant_measurements.txt')){
    system(sprintf('cp %s %s',
                   file.path(pd.dir, 'rizvi2015', 'processed', f),
                   file.path(pd.dir, 'template', 'processed', f)))
}

# TCGA
for (f in c('samples.tsv',
            'subjects.tsv')){
    system(sprintf('cp %s %s',
                   file.path(tcga.dir, 'processed', f),
                   file.path(pd.dir, 'template', 'processed', paste0('tcga-', f))))
}
for (f in c('cnv/cnv_meas_1.tsv',
            'cnv/cnv_meas_3.tsv',
            'cnv/cnv_ref_1.tsv',
            'cnv/cnv_ref_3.tsv',
            'gc/gc_ref_cnv_1.tsv',
            'gc/gc_ref_cnv_3.tsv',
            'variants/variant_meas_21.tsv',
            'variants/variant_meas_32.tsv',
            'variants/variant_ref_21.tsv',
            'variants/variant_ref_32.tsv',
            'gc/gc_ref_var_21.tsv',
            'gc/gc_ref_var_32.tsv')){
    system(sprintf('cp %s %s',
                   file.path(tcga.dir, 'processed', f),
                   file.path(pd.dir, 'template', 'processed', strsplit(f, '/')[[1]][2])))
}

# PICI0002
system(sprintf('cp %s %s',
               file.path(pd.dir, 'pici0002', 'processed', 'cell_populations_*'),
               file.path(pd.dir, 'template', 'processed/')))



####### load in samples files for getting IDs and putting in other meas files
s.riz = fread('rizvi-samples.txt')
s.tcga = fread('tcga-samples.tsv')


####### Rizvi variants - downsample for speed
v = fread(file.path(pd.dir, 'rizvi2015', 'processed', 'variants.txt'))
v = v[sample(nrow(v), num.variants), ]
fwrite(v, 'variants.txt', sep = '\t')

v.m = fread(file.path(pd.dir, 'rizvi2015', 'processed', 'variant_measurements.txt'))
v.m = v.m[v.m$var.id %in% v$var.id, ]
v.m = v.m[v.m$sample.id %in% s.riz$sample.id, ]
fwrite(v.m, 'variant_measurements.txt', sep = '\t')


####### sample IDs on LDH
ldh = fread(file.path(pd.dir, 'roh2017', 'processed', 'ldh_meas_and_samples.txt'))
ldh$`Patient ID` = sample(s.riz$subject.id, nrow(ldh))
ldh$sample.id = paste0(ldh$`Patient ID`, '-baseline')
ldh = ldh[!is.na(ldh$`LDH @ Baseline`), ]
fwrite(ldh, 'ldh_meas_and_samples.txt', sep = '\t', na = NA)


####### sample IDs on TCR
tcr = fread(file.path(pd.dir, 'roh2017', 'processed', 'tcr_clonality.txt'))
tcr$sample = sample(s.tcga$barcode, nrow(tcr))
tcr = tcr[, .(sample, Clonality)]
fwrite(tcr, 'tcr_clonality.txt', sep = '\t')


####### sample IDs on Nanostring
n = fread(file.path(pd.dir, 'roh2017', 'processed', 'nanostring.txt'))
n$sample = s.tcga$barcode[strtoi(n$sample, base = 32)]
fwrite(n, 'nanostring.txt', sep = '\t')


####### CyTOF sample IDs for several meas files
cytof.files = c('cytof_measurements_clusters_Spitzer.txt',
                'cytof_measurements_eventCount_Spitzer.txt',
                'cytof_measurements_median_Spitzer.txt')
for (cf.file in cytof.files){
    sc = fread(file.path(pd.dir, 'pici0002', 'processed', cf.file))
    sc = sc[sample(nrow(sc), num.cytof.meas), ]
    sc$sample.ind = match(sc$sample, unique(sc$sample))
    sc$sample = s.tcga$barcode[sc$sample.ind]
    write.table(sc, cf.file, row.names = F, col.names = T, quote = F, sep = "\t")
}

####### sample IDs on TCGA files, apparently those are bad
cnv = fread('cnv_meas_1.tsv')
cnv$barcode = 'TCGA-02-0055-01A-01D-1490-08'
fwrite(cnv, 'cnv_meas_1.tsv', sep = '\t')

cnv = fread('cnv_meas_3.tsv')
cnv$barcode = 'TCGA-02-2483-01A-01R-1849-01'
fwrite(cnv, 'cnv_meas_3.tsv', sep = '\t')

