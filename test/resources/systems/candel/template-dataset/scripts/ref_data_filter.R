rm(list = ls())
library(data.table)
library(wick)

# when updating reference data, request a new db from schema version with
# ref data updates to run scripts
wick::login()
set_dbname("ref-data")
all.genes <- wick::get_all_genes()

# Set root to this project (template)'s root directory.
self_root <- ("~/azure-datasets/template")
f1path <- paste(self_root, "processed/cnv_ref_1.tsv", sep = '/')
f2path <- paste(self_root, "processed/cnv_ref_3.tsv", sep = '/') 
cnv_1 <- fread(f1path)
cnv_3 <- fread(f2path)

# Note: must define all.genes in global vars for this to work!
# Takes genes of form: MTF1;MTFR1L;MTHFR;MTMR9LP;MTOR;MTOR-AS1;MUL1 and expands
# to a column to remap via wick::map_gene_symbols, then collapses back to a
# string. Can be called with e.g. sapply
remap.multi <- function(x) {
  expanded <- strsplit(x, split = ";")
  names(expanded) <- c("Gene")
  remapped <- wick::map_gene_symbols(all.genes, expanded$Gene)
  fixed <- paste(na.omit(remapped), collapse = ";")
  return(fixed)
}

# expand each field to a column
# map gene symbols on column
# collapse back to a single row entry
cnv_1$Genes <- sapply(cnv_1$Genes, remap.multi)
cnv_3$Genes <- sapply(cnv_3$Genes, remap.multi)

# these cnv files will now be fixed
fwrite(cnv_1, 'cnv_ref_fixed_1.tsv', sep = '\t')
fwrite(cnv_3, 'cnv_ref_fixed_3.csv', sep = '\t')

# fix old tcga variant files
vf1 <- paste(self_root, 'processed/variant_ref_21.tsv', sep = '/')
vf2 <- paste(self_root, 'processed/variant_ref_32.tsv', sep = '/')
vf_df1 <- fread(vf1)
vf_df2 <- fread(vf2)

# messy but adequate
vf_df1[variable == 'Hugo_Symbol']$value <-
  map_gene_symbols(all.genes, vf_df1[variable == 'Hugo_Symbol']$value)
vf_df2[variable == 'Hugo_Symbol']$value <-
  map_gene_symbols(all.genes, vf_df2[variable == 'Hugo_Symbol']$value)

# using version control to revert if it looks wrong
fwrite(vf_df1, vf1, sep = '\t')
fwrite(vf_df2, vf2, sep = '\t')

