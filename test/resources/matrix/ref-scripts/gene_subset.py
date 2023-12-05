import pandas as pd

# read in both matrix files used in tests
sparse = pd.read_csv('short-processed-counts.tsv', sep='\t', index_col='barcode')
dense = pd.read_csv('dense-rnaseq.tsv', sep='\t', index_col='sample.id')

# get list of all hgnc symbols
hgnc_path = "../../../seed_data/raw/genes/hgnc_complete_set.txt"
hgnc = pd.read_csv(hgnc_path, sep='\t')
hugos = set(hgnc['symbol'])

# only keep columns for dense matrix
# note: this 'fixes' test data, but for completeness with real data,
#       you should use aliases to remap, e.g. in wick::map_gene_symbols
keep = [col for col in dense.columns if col in hugos]
dense_fixed = dense[keep]
dense_fixed.to_csv('dense-rnaseq.tsv', sep='\t')

# drop rows in sparse matrix that aren't in new hugo list
sparse_fixed = sparse[sparse['hugo'].apply(lambda sym: sym in hugos)]
sparse_fixed.to_csv('short-processed-counts.tsv', sep='\t')

