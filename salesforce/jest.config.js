const { jestConfig } = require('@salesforce/sfdx-lwc-jest/config');

module.exports = {
    ...jestConfig,
    // The Salesforce Local Development Server writes copies of components here; Jest would see them as duplicate modules.
    modulePathIgnorePatterns: ['<rootDir>/.localdevserver']
};
